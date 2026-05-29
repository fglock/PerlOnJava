use strict;
use warnings;
use Test::More;

sub chr_from_shift {
    return chr(shift);
}

my $text = '#65';
ok($text =~ /^#([0-9]+)$/, 'decimal capture matched');
is(chr($1), 'A', 'chr numifies a regex capture variable');
is(chr_from_shift($1), 'A', 'chr numifies a regex capture passed through shift');
is($1 << 1, 130, 'bitshift numifies a regex capture variable');

done_testing;
