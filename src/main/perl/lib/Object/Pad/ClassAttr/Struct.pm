package Object::Pad::ClassAttr::Struct;

use strict;
use warnings;

our $VERSION = '0.06';

# PerlOnJava implements :Struct(readonly) in its class parser. The upstream
# extension's XS post-seal hook is unnecessary for that supported subset.
sub import { }
sub unimport { }

1;
