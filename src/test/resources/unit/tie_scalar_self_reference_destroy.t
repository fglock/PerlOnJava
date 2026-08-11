use strict;
use warnings;

use Test::More tests => 1;

my $destroyed = 0;

sub Local::SelfTie::TIESCALAR {
    bless $_[1], $_[0];
}

sub Local::SelfTie::DESTROY {
    $destroyed++;
}

{
    my $value = 42;
    tie $value, 'Local::SelfTie', \$value;
}

is($destroyed, 1, 'a scalar self-tie is destroyed when its lexical leaves scope');
