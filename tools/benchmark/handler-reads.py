"""Measure session Handler_read_* deltas without changing data or indexes."""
import argparse
import csv
import hashlib
import json
import os
from pathlib import Path
import subprocess

INDEX_PRODUCT = 'idx_products_brand_deleted_price_id'
INDEX_LIKE = 'idx_product_likes_product_id'
PHASES = ('no-query-indexes', 'product-index-only', 'both-indexes')


def mysql(sql):
    command = ['docker', 'exec', '-i', '-e', 'MYSQL_PWD',
               'loopers-offset-benchmark-mysql-benchmark-1', 'mysql', '-uroot',
               '--database=loopers_offset_bench', '--batch', '--raw', '--default-character-set=utf8mb4']
    environment = dict(os.environ, MYSQL_PWD=os.environ.get('MYSQL_PWD', 'benchmark-root'))
    return subprocess.check_output(command, input=sql, text=True, env=environment, timeout=120)


def section(raw, start, end):
    lines = raw.splitlines()
    return [line for line in lines[lines.index(start) + 1:lines.index(end)] if line != 'section']


def measure(sql, output, expected):
    session_sql = f"""START TRANSACTION READ ONLY;
SELECT '__SESSION_BEFORE__' AS section;
SELECT CONNECTION_ID() AS connection_id;
SELECT '__HANDLER_BEFORE__' AS section;
SHOW SESSION STATUS LIKE 'Handler_read%';
SELECT '__RESULT__' AS section;
{sql};
SELECT '__HANDLER_AFTER__' AS section;
SHOW SESSION STATUS LIKE 'Handler_read%';
SELECT '__SESSION_AFTER__' AS section;
SELECT CONNECTION_ID() AS connection_id;
SELECT '__END__' AS section;
ROLLBACK;
"""
    raw = mysql(session_sql)
    output.with_suffix('.sql').write_text(session_sql)
    output.with_suffix('.tsv').write_text(raw)
    result = section(raw, '__RESULT__', '__HANDLER_AFTER__')
    assert [line.split('\t') for line in result[1:]] == expected, 'SELECT result differs from the baseline'
    def counters(start, end):
        return {line.split('\t')[0]: int(line.split('\t')[1])
                for line in section(raw, start, end) if line.startswith('Handler_read')}
    before = counters('__HANDLER_BEFORE__', '__RESULT__')
    after = counters('__HANDLER_AFTER__', '__SESSION_AFTER__')
    assert before.keys() == after.keys() and before
    first_id = int(section(raw, '__SESSION_BEFORE__', '__HANDLER_BEFORE__')[1])
    last_id = int(section(raw, '__SESSION_AFTER__', '__END__')[1])
    assert first_id == last_id
    delta = {key: after[key] - before[key] for key in before}
    assert all(value >= 0 for value in delta.values())
    return {'connection_id_before': first_id, 'connection_id_after': last_id,
            'before': before, 'after': after, 'delta': delta,
            'result_equal': True, 'rows_returned': len(expected)}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--source', type=Path, required=True,
                        help='External baseline folder containing queries.sql and http/response.json')
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--samples', type=int, default=3)
    args = parser.parse_args()
    if not 1 <= args.samples <= 10:
        parser.error('samples must be between 1 and 10')
    args.output.mkdir(parents=True, exist_ok=False)
    source = args.source
    queries = [query.strip() for query in (source / 'queries.sql').read_text().split(';') if query.strip()]
    assert len(queries) == 2 and all(query.lower().startswith('select ') for query in queries)
    baseline = json.loads((source / 'http/response.json').read_text())['data']
    expected_list = [[str(row['productId']), row['name'], str(row['price']),
                      str(row['brand']['brandId']), row['brand']['name'], str(row['likeCount'])]
                     for row in baseline['items']]
    expected_count = [[str(baseline['totalElements'])]]
    environment_sql = '''SELECT DATABASE() AS target_db,@@version AS version;
SELECT COUNT(*) AS products FROM products;SELECT COUNT(*) AS likes FROM product_likes;
SHOW CREATE TABLE products;SHOW CREATE TABLE product_likes;
'''
    environment = mysql(environment_sql)
    (args.output / 'environment-before.tsv').write_text(environment)
    assert '\n1000000\n' in environment and '\n2000000\n' in environment
    assert INDEX_PRODUCT in environment and INDEX_LIKE in environment
    records = []
    for sample in range(1, args.samples + 1):
        row = measure('SELECT 1 AS value', args.output / f'control-{sample}', [['1']])
        records.append(dict(phase='observer-control', query='constant', sample=sample, **row))
    for phase in PHASES:
        folder = args.output / phase
        folder.mkdir()
        for query_name, query, expected in zip(('list', 'count'), queries, (expected_list, expected_count)):
            if phase == 'no-query-indexes':
                query = query.replace('products p', f'products p IGNORE INDEX ({INDEX_PRODUCT})', 1)
            if phase != 'both-indexes':
                query = query.replace('product_likes l', f'product_likes l IGNORE INDEX ({INDEX_LIKE})', 1)
            (folder / f'{query_name}-query.sql').write_text(query + ';\n')
            for sample in range(1, args.samples + 1):
                row = measure(query, folder / f'{query_name}-{sample}', expected)
                records.append(dict(phase=phase, query=query_name, sample=sample, **row))
            # Plan collection uses another connection, outside the measured counter interval.
            (folder / f'{query_name}-explain.tsv').write_text(mysql('EXPLAIN ' + query + ';'))
            (folder / f'{query_name}-analyze.txt').write_text(mysql('EXPLAIN ANALYZE ' + query + ';'))
        print('Collected:', phase, flush=True)
    final_environment = mysql(environment_sql)
    (args.output / 'environment-after.tsv').write_text(final_environment)
    assert environment == final_environment, 'Dataset counts or index DDL changed'
    all_keys = sorted(records[0]['delta'])
    with (args.output / 'deltas.csv').open('w', newline='') as stream:
        writer = csv.writer(stream)
        writer.writerow(['phase', 'query', 'sample'] + all_keys)
        for row in records:
            writer.writerow([row['phase'], row['query'], row['sample']] + [row['delta'][key] for key in all_keys])
    report = {'method': 'Same-session before/after differences around one SELECT in a read-only transaction.',
              'index_control': 'Query-local IGNORE INDEX, no persistent DDL or global setting changes.',
              'scope': 'Direct SQL counter validation, not another HTTP latency benchmark.',
              'samples_per_query_per_phase': args.samples,
              'source_script_sha256': hashlib.sha256(Path(__file__).read_bytes()).hexdigest(),
              'baseline_queries_sha256': hashlib.sha256((source / 'queries.sql').read_bytes()).hexdigest(),
              'all_results_equal': all(row['result_equal'] for row in records),
              'dataset_and_indexes_unchanged': environment == final_environment,
              'records': records}
    (args.output / 'results.json').write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n')
    print('Completed; all SELECT results equal and dataset/index DDL unchanged.', flush=True)


if __name__ == '__main__':
    main()
