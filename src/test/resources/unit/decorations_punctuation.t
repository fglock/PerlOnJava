use strict;
use warnings;
use Test::More;

my $source = <<'PERL';
* * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * *
/ / / / / / / / / / / / / / / / / / / / / / / / / / / / / / / / / 
% % % % % % % % % % % % % % % % % % % % % % % % % % % % % % % %;
BEGIN {% % = ($ _ = " " => print "Just another Perl Hacker\n")}
PERL

open my $child, '-|', $^X, '-e', $source
    or die "run decoration source: $!";
my $output = do { local $/; <$child> };
close $child or die "close decoration source: $?";

is($output, "Just another Perl Hacker\n",
   'punctuation-only decoration lines have no runtime effect');

done_testing();
