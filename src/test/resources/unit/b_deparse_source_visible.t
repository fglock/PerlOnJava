use strict;
use warnings;
use Test::More;
use B::Deparse;
use File::Temp qw(tempfile);

my $deparse = B::Deparse->new;
my ($fh, $path) = tempfile('perlonjava_b_deparse_source_visible_XXXX', SUFFIX => '.pl', TMPDIR => 1, UNLINK => 0);
print {$fh} "our \$CODE = sub { 0 };\n1;\n";
close $fh or die "close $path: $!";

our $CODE;
my $loaded = do $path;
die $@ if $@;
die "do $path: $!" unless defined $loaded;

is($deparse->coderef2text($CODE), '{ 0; }', 'source-visible anonymous sub deparses from file context');

unlink $path;

done_testing();
