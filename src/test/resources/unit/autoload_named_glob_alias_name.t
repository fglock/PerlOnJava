use strict;
use warnings;
use Test::More tests => 1;

our $AUTOLOAD;
our $seen;
sub AUTOLOAD { $seen = $AUTOLOAD; return }

{
    no strict 'refs';
    *{'main::alias'} = *{'main::target'};
}

alias();
is($seen, 'main::alias', 'AUTOLOAD reports the called glob alias');
