use strict;
use warnings;
use Test::More;
use File::Temp qw(tempfile);

my ($fh, $path) = tempfile('perlonjava_b_deparse_prototype_block_XXXX', SUFFIX => '.pl', TMPDIR => 1, UNLINK => 0);
print {$fh} <<'PERL';
use strict;
use warnings;
use B::Deparse;

sub capture (&) {
    return B::Deparse->new->coderef2text($_[0]);
}

my $foo = 1;
my $bar = 2;
our $TEXT = eval { capture { $foo == $bar } };
1;
PERL
close $fh or die "close $path: $!";

our $TEXT;
my $loaded = do $path;
die $@ if $@;
die "do $path: $!" unless defined $loaded;

like($TEXT, qr/\$foo == \$bar/, 'prototype block source is visible to B::Deparse');

unlink $path;

done_testing();
