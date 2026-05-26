use strict;
use warnings;
use Test::More;
use B::Deparse;

my $deparse = B::Deparse->new;
my $path = "/tmp/perlonjava_b_deparse_source_visible_$$.pl";
open my $fh, '>', $path or die "open $path: $!";
print {$fh} "our \$CODE = sub { 0 };\n1;\n";
close $fh or die "close $path: $!";

our $CODE;
my $loaded = do $path;
die $@ if $@;
die "do $path: $!" unless defined $loaded;

is($deparse->coderef2text($CODE), '{ 0; }', 'source-visible anonymous sub deparses from file context');

unlink $path;

done_testing();
