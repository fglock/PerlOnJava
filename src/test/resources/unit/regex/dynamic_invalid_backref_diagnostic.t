use strict;
use warnings;
use Test::More tests => 1;
use re 'eval';

eval 'qr/(?{})\6/';
like($@, qr/Reference to nonexistent group/,
    'dynamic regex reports Perl nonexistent-group diagnostic');
