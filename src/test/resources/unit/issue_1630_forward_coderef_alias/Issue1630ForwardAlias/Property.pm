package Issue1630ForwardAlias::Property;

use strict;
use warnings;

*convert = \&Issue1630ForwardAlias::Config::convert;

sub call { Issue1630ForwardAlias::Property::convert($_[0]) }

1;
