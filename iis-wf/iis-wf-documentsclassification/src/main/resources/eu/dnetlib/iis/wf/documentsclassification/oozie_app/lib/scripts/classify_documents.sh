#!/bin/bash
set -o pipefail
python $1/madis/mexec.py -d $1/taxonomies.db -f $1/classify.sql | python $1/complete_taxonomies.py