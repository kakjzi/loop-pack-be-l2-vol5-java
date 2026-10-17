"""Read-only sequential HTTP samples for the fixed week-4 price query."""
import argparse
import csv
import hashlib
import http.client
import json
import math
from pathlib import Path
import statistics
import time
from urllib.parse import urlencode, urlsplit


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--base-url', default='http://127.0.0.1:8082')
    parser.add_argument('--brand-id', type=int, required=True)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--compare', type=Path, help='A previous response.json with the same seed and request')
    parser.add_argument('--warmups', type=int, default=5)
    parser.add_argument('--samples', type=int, default=30)
    args = parser.parse_args()
    base = urlsplit(args.base_url)
    if (base.scheme != 'http' or base.hostname not in ('127.0.0.1', 'localhost')
            or base.path not in ('', '/') or base.query or base.fragment
            or base.username or base.password):
        parser.error('Use an HTTP loopback base URL, for example http://127.0.0.1:8082')
    if args.brand_id <= 0 or args.warmups < 0 or args.samples < 2:
        parser.error('brand-id must be positive, warmups nonnegative, samples at least 2')
    request = '/api/v1/products?' + urlencode({
        'brandId': args.brand_id, 'sort': 'price_asc', 'page': 0, 'size': 20,
    })
    args.output.mkdir(parents=True, exist_ok=False)
    expected = json.loads(args.compare.read_text()) if args.compare else None
    connection = http.client.HTTPConnection(base.hostname, base.port or 80, timeout=30)
    samples = []
    try:
        for index in range(args.warmups + args.samples):
            start = time.perf_counter_ns()
            connection.request('GET', request)
            response = connection.getresponse()
            raw = response.read()
            elapsed_ms = (time.perf_counter_ns() - start) / 1_000_000
            if response.status != 200:
                raise RuntimeError(f'HTTP {response.status}: {raw[:1000]!r}')
            actual = json.loads(raw)
            (args.output / 'response.json').write_text(json.dumps(actual, ensure_ascii=False, indent=2) + '\n')
            if actual.get('meta', {}).get('result') != 'SUCCESS':
                raise RuntimeError('Expected successful API envelope')
            page = actual['data']
            if page['page'] != 0 or page['size'] != 20 or len(page['items']) != 20:
                raise RuntimeError('Expected a full first page of size 20')
            if expected is None:
                expected = actual
            if actual != expected:
                raise RuntimeError('Full response differs; inspect response.json and the comparison input')
            if index >= args.warmups:
                samples.append(elapsed_ms)
    finally:
        connection.close()
        with (args.output / 'latencies.csv').open('w', newline='') as output:
            writer = csv.writer(output)
            writer.writerow(['sample', 'elapsed_ms'])
            writer.writerows((i + 1, value) for i, value in enumerate(samples))
    ordered = sorted(samples)
    canonical = json.dumps(expected, ensure_ascii=False, sort_keys=True, separators=(',', ':')).encode()
    summary = {
        'url': args.base_url.rstrip('/') + request,
        'warmups': args.warmups, 'samples': len(samples), 'concurrency': 1,
        'connection': 'HTTP/1.1 reused when server permits',
        'p50_ms': statistics.median(samples),
        'p95_ms': ordered[math.ceil(len(ordered) * 0.95) - 1],
        'min_ms': min(samples), 'max_ms': max(samples),
        'total_elements': expected['data']['totalElements'],
        'response_sha256': hashlib.sha256(canonical).hexdigest(),
        'response_equal_for_all_requests': True,
        'scope': 'Warm sequential loopback HTTP; not a throughput or cold-start benchmark',
    }
    (args.output / 'summary.json').write_text(json.dumps(summary, ensure_ascii=False, indent=2) + '\n')
    print(json.dumps(summary, ensure_ascii=False, indent=2))


if __name__ == '__main__':
    main()
