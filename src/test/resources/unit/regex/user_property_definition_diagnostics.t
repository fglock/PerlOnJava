use strict;
use warnings;
use utf8;
use Test::More tests => 12;

sub IsOverflow { return "0\t80000000000000000#ネ" }
sub IsRangeReversed { return "200 100#ネ" }
sub IsNonHex { return "BEEF CAGED#ネ" }
sub IsDeath { die "intentional property death\n" }
sub InRecursedA { return "+main::InRecursedB\n" }
sub InRecursedB { return "+main::InRecursedC\n" }
sub InRecursedC { return "+main::InRecursedA\n" }
sub InOneBadApple { return "0100\t0110\n10000\t10010\nF000\\tF010\n0400\t0410" }

my @cases = (
    [ IsOverflow => qr/^Code point too large in "0\t80000000000000000#ネ" in expansion of main::IsOverflow/ ],
    [ IsRangeReversed => qr/^Illegal range in "200 100#ネ" in expansion of main::IsRangeReversed/ ],
    [ IsNonHex => qr/^Can't find Unicode property definition "BEEF CAGED" in expansion of main::IsNonHex/ ],
    [ IsDeath => qr/^Error "intentional property death\n" in expansion of main::IsDeath/s ],
    [ InRecursedA => qr/^Infinite recursion in user-defined property "main::InRecursedA" in expansion of main::InRecursedC in expansion of main::InRecursedB in expansion of main::InRecursedA/ ],
);

for my $case (@cases) {
    my ($property, $diagnostic) = @$case;
    my $source = 'qr/\p{main::' . $property . '}/';
    my $ok = eval "$source; 1";
    ok(!$ok, "$property definition is rejected");
    like($@, $diagnostic, "$property reports the Perl expansion diagnostic");
}

my $nested_ok = eval q{qr/\p{InOneBadApple}/; 1};
ok(!$nested_ok, 'a bad nested component rejects the user property');
like($@, qr/^Can't find Unicode property definition "F000\\tF010" in expansion of InOneBadApple/,
    'a bad nested component reports the unqualified expansion name');
