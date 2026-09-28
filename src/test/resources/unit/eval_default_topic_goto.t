use strict;
use warnings;
use Test::More;
use File::Temp qw(tempfile);

# `eval` without an explicit operand evaluates $_.  A goto returned by that
# string must resume in the caller's label scope, rather than being treated as
# the value of the eval expression.
my ($script, $path) = tempfile();
print {$script} <<'PROGRAM';
$_ = "goto F.print chop;\n=rekcaH lreP rehtona tsuJ";
F1: eval;
PROGRAM
close $script or die "close child script: $!";

open my $child, '-|', $^X, $path or die "run child: $!";
my $out = do { local $/; <$child> };
close $child or die "close child: $!";
unlink $path or die "unlink child script: $!";

is($out, 'Just another Perl Hacker',
   'implicit-topic eval resolves a goto in the caller scope');

done_testing();
