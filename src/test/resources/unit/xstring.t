use strict;
use warnings;
use Test::More;

eval { require XString; 1 }
    or plan skip_all => 'XString required';

is(XString->VERSION, '0.005', 'bundled XString version matches CPAN prereq');
is(XString::cstring(q[a$@"]), q["a$@\""], 'cstring does not escape Perl sigils');
is(XString::perlstring(q[a$@"]), q["a\$\@\""], 'perlstring escapes Perl sigils');

for my $ord (0, 7, 9, 10, 13, 127, 255, 256) {
    my $char = chr($ord);
    my $quoted = XString::perlstring($char);
    my $roundtrip = eval $quoted;
    is($roundtrip, $char, "perlstring round-trips codepoint $ord");
}

done_testing();
