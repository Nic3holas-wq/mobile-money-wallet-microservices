#!/bin/sh
set -eu

until pg_isready -h postgresql -U nicko -d postgres; do
  sleep 2
done

for database in customer_db wallet_db payment_db; do
  exists=$(psql -h postgresql -U nicko -d postgres -tAc \
    "SELECT EXISTS (SELECT 1 FROM pg_database WHERE datname = '$database')")
  if [ "$exists" != "t" ]; then
    createdb -h postgresql -U nicko "$database"
    echo "Created database: $database"
  else
    echo "Database already exists: $database"
  fi
done
