use Test::More tests => 1;

{
    local *ARGV = *DATA;
    my $readline = \&CORE::readline;
    is(scalar $readline->(), "first data line\n",
        'CORE::__DATA__ initializes DATA for readline');
}

CORE::__DATA__
first data line
second data line
