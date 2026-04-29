use strict;
use lib -e 't' ? 't' : 'test';
use TestYAML tests => 8;

run_load_passes();

__DATA__

=== Bug reported by Rich Morin
+++ SKIP
+++ yaml
foo:
  -   >
    This is a test.

=== Bug reported by audreyt
+++ SKIP
+++ yaml
--- "\n\
\r"

===
+++ yaml
---
foo:
    bar:
          baz:
                  poo: bah


===
+++ yaml
--- 42


===
+++ yaml
# comment
--- 42
# comment


===
+++ yaml
--- [1, 2, 3]


===
+++ yaml
--- {foo: bar, bar: 42}


===
+++ yaml
--- !foo.com/bar
- 2


===
+++ yaml
--- &1 !foo.com/bar
- 42


===
+++ yaml
---
  - 40
  - 41
  - foof
