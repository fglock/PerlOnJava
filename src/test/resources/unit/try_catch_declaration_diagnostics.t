use strict;
use warnings;
use feature 'try';
use Test::More;

plan skip_all => 'catch declaration diagnostic changed in Perl 5.44'
    if $^V lt v5.44.0;

for my $declaration (qw(my our state)) {
    my $prefix = $declaration eq 'state' ? "use feature 'state';\n" : '';
    my $ok = eval $prefix . "try {} catch ($declaration \$error) {}";
    ok(!$ok, "catch ($declaration) fails");
    like($@, qr{\ACan't redeclare catch variable as "\Q$declaration\E" at \(eval \d+\) line \d+, near "\(\Q$declaration\E"\nsyntax error at \(eval \d+\) line \d+, near "\(\Q$declaration\E "},
         "catch ($declaration) reports both Perl diagnostics");
}

done_testing;
