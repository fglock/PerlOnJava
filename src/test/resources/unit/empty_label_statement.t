use strict;
use warnings;
use Test::More;

my $source = <<'PERL';
:$:=~s:$":Just$&another$&:;$:=~s:
:Perl$"Hacker$&:;chop$:;print$:#:
PERL
open my $child, '-|', $^X, '-e', $source
    or die "run source with empty label: $!";
my $output = do { local $/; <$child> };
close $child or die "close source: $?";

is($output, "Just another Perl Hacker\n",
   'an empty label separates a statement beginning with $:');

done_testing();
