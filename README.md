# Filter large lists of integers using ElasticSearch with RoaringBitmaps

### Goal

The plugin lets you **restrict search hits using a very large set of integer
values** on a mapped numeric field (for example millions of account or entity
IDs). You ship that set as a **single, compact base64 RoaringBitmap** in the
script params instead of exploding it into a giant `terms` query, paginating
aggregations to discover IDs, or working around **aggregation bucket limits**,
`**terms` query size limits**, and similar **caps on how many distinct values**
you can push through the API in one step.

Typical motivation: you already know the allow-list or deny-list (from offline
analytics, another system, or a prior job) and you want **one search** that
filters on that full set **without** being bounded by `terms` aggregation
sizes, composite aggregation page sizes, or request bodies containing huge
literal arrays.

### When you get a performance (and operability) boost

- **Very large membership sets** (thousands to millions of integers): the bitmap
stays small on the wire and in memory compared to a massive `terms` clause or
repeated queries to stitch results together.
- **Avoiding multi-step pipelines** that exist only to stay under **per-request
term counts** or **agg `size` / bucket limits**: you filter directly in the
query phase with the full set encoded once.
- **Pairs well with other query clauses** (for example `bool.filter` alongside
full-text or range filters) while keeping the huge ID set as one opaque
parameter.

**Caveat:** Matching still uses a **filter script** that consults doc values
per candidate document, so for **tiny** sets a plain `terms` query is often
simpler and can be faster. This plugin is aimed at the regime where
**traditional approaches hit limits or unacceptable request size**, not at
replacing `terms` for dozens of IDs.

### Results: you are still subject to normal search and aggregation limits

The bitmap is **input to the filter only**. Elasticsearch does **not** return
a RoaringBitmap of matching `nid` values, and this plugin does not add a custom
response type.

- **Hits:** Each response returns up to `size` documents (and `from` /
`search_after` / point-in-time rules still apply). **`track_total_hits`**
and index settings still govern total-hit reporting. Nothing here raises
`max_result_window` or similar caps.
- **Aggregations:** If you use a `terms` (or other) aggregation on `nid` to
list distinct values, you still hit that aggregation’s `**size`**,
`**shard_size**`, and related limits unless you page (for example composite)
or change another workflow.
- **Getting “all matching nids”:** You still **stream or page** (for example
`search_after` + `_source`/`docvalue_fields` for `nid`, or scroll/PIT
patterns), then **build your own bitmap** in the client if you want a compact
offline artifact.

So the win is on the **query side** (huge membership as one param). **Output**
remains whatever the APIs you use for hits or aggs allow.

Based on fastfilter-elasticsearch-plugin by Luis Sena, updated for
ElasticSearch 9.2

## Installation

The plugin ships as a `**.zip` archive**. That zip is **not** created by
`elasticsearch-plugin`; Gradle **builds** it from this repository. The
`elasticsearch-plugin` command only **installs** an existing zip into an
Elasticsearch installation (or Docker image).

### Build the zip from source

From the repository root:

```
./gradlew clean build
```

The zip appears under `**build/distributions/**` (for example
`roaringfilter-elasticsearch9-plugin-0.1.zip`; exact name comes from
`gradle.properties`).

### Install into a local Elasticsearch (tarball or package)

`bin/elasticsearch-plugin` lives **inside your Elasticsearch install**, not in
this repo. Use the full path to the zip you built (or downloaded):

```
Linux:
bin/elasticsearch-plugin install file:/absolute/path/to/build/distributions/roaringfilter-....zip

Windows:
bin\elasticsearch-plugin install file:///c:/absolute/path/to/build/distributions/roaringfilter-....zip
```

### Install into Docker

Elasticsearch in Docker does not put `elasticsearch-plugin` on your host. Build
the zip on the host (see above), copy it into the container, install, then
restart the container. Example:

```
docker cp build/distributions/roaringfilter-elasticsearch9-plugin-0.1.zip my-es:/tmp/plugin.zip
docker exec -u elasticsearch -it my-es \
  /usr/share/elasticsearch/bin/elasticsearch-plugin install --batch file:///tmp/plugin.zip
```

Replace `my-es` with your container name and adjust the zip filename if needed.
Use an Elasticsearch **image version that matches** this plugin’s target (see
`elasticsearchVersion` in `gradle.properties`).

### Install a release zip from GitHub (no local build)

```
bin/elasticsearch-plugin install https://github.com/derekwsgray/roaringfilter-elasticsearch9-plugin/releases/download/v0.1/roaringfilter-elasticsearch-plugin-0.1.zip?raw=true
```

Inside Docker, use the same URL with `elasticsearch-plugin install` via
`docker exec`, or bake the `RUN ... install` step into a custom image.

More detail:
[plugin-management-custom-url](https://www.elastic.co/guide/en/elasticsearch/plugins/current/plugin-management-custom-url.html)

## Usage

```json
GET /test/_search
{
  "query": {
    "bool": {
      "filter": {
        "script": {
          "script": {
            "source": "roaring_filter",
            "lang": "roaring_filter",
            "params": {
              "field": "nid",
              "operation": "include",
              "terms": "OjAAAAEAAAAAAAIAEAAAAAoAFAAjAA=="
            }
          }
        }
      }
    }
  }
}
```

This will fetch documents whose numeric field `nid` is one of 10, 20, or 35.
Map `nid` as a numeric type (for example `integer` or `long`) so the plugin can
read sorted numeric doc values. This is not Elasticsearch’s document metadata
`_id`; use a real mapped field such as `nid`.

## Python Example

```python
from pyroaring import BitMap
import base64

from elasticsearch import Elasticsearch


if __name__ == "__main__":
    es = Elasticsearch()
    # In production, the bitmap often encodes a large id set; you can precompute
    # and reuse the serialized form.
    bm = BitMap([10, 20, 35])

    # Match documents where mapped numeric field `nid` is in the bitmap (not `_id`).
    result = es.search(
        index="user-index",
        body={
            "query": {
            "bool": {
              "must": [
                {
                  "match": {
                    "important_field": "foo"
                  }
                }
              ],
              "filter": {
                "script": {
                  "script": {
                    "source": "roaring_filter",
                    "lang": "roaring_filter",
                    "params": {
                      "field": "nid",
                      "operation": "include",
                      "terms": base64.b64encode(BitMap.serialize(bm))
                    }
                  }
                }
              }
            }
          }
        }
    )
```

