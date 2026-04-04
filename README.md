Filter large lists of integers using ElasticSearch with RoaringBitmaps
===========================================

This filter plugin uses RoaringBitmap to allow efficient filtering with hundreds of thousands (even millions) on ElasticSearch.

Based on fastfilter-elasticsearch-plugin by Luis Sena, updated for ElasticSearch 9.2

Installation
------------

In order to install a stable version of the plugin, 
run ElasticSearch's `plugin` utility:

    bin/elasticsearch-plugin install https://github.com/derekwsgray/roaringfilter-elasticsearch9-plugin/releases/download/v0.1/roaringfilter-elasticsearch-plugin-0.1.zip?raw=true


To install from sources (master branch), run:

    gradle clean build

then install with (use full path):

    Linux:
    bin/elasticsearch-plugin install file:/.../(plugin)/build/distributions/*.zip

    Windows:
    bin\elasticsearch-plugin install file:///c:/.../(plugin)/build/distributions/*.zip

More information here: https://www.elastic.co/guide/en/elasticsearch/plugins/current/plugin-management-custom-url.html

Usage
-----

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
This will fetch documents whose numeric field `nid` is one of 10, 20, or 35. Map `nid` as a numeric type (for example `integer` or `long`) so the plugin can read sorted numeric doc values. This is not Elasticsearch’s document metadata `_id`; use a real mapped field such as `nid`.

Python Example
-----


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
