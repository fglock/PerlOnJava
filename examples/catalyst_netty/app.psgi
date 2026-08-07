use strict;
use warnings;
use FindBin;
use lib "$FindBin::Bin/lib";
use CatalystNetty;

CatalystNetty->psgi_app;
