use strict;
use warnings;
use Test::More tests => 2;

use Config;

ok(-f "$Config{privlibexp}/strict.pm", 'privlibexp contains strict.pm probe');
ok(-f "$Config{privlibexp}/File/Find.pm", 'privlibexp contains File::Find probe');
