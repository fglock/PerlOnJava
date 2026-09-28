use strict;
use warnings;
use Test::More;
use File::Temp qw(tempfile);

# `%;` is a legal punctuation-named hash.  With no whitespace after print,
# the leading percent must remain its sigil instead of being parsed as modulo.
my ($script, $path) = tempfile();
print {$script} <<'PROGRAM';
$;=$";$;{Just=>another=>Perl=>Hacker=>}=$/;print%;
PROGRAM
close $script or die "close child script: $!";

open my $child, '-|', $^X, $path or die "run child: $!";
my $out = do { local $/; <$child> };
close $child or die "close child: $!";
unlink $path or die "unlink child script: $!";

is($out, "Just another Perl Hacker\n", 'print%; parses %; as its argument');

done_testing();
