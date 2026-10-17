select p.id as productId, p.name as name, p.price as price,
       b.id as brandId, b.name as brandName,
       (select count(*) from product_likes l where l.product_id=p.id) as likeCount
from products p join brands b on b.id=p.brand_id
where p.deleted_at is null and b.deleted_at is null and p.brand_id=1
order by p.price asc,p.id desc limit 20 offset 0;
select count(*) from products p join brands b on b.id=p.brand_id
where p.deleted_at is null and b.deleted_at is null and p.brand_id=1;
