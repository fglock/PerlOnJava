use strict;
use warnings;
use Test::More tests => 4;
use IO::Handle;
use Scalar::Util qw(reftype);

my $path = "/tmp/perlonjava-glob-scalar-ref-$$.txt";
open my $out, '>', $path or die "open $path: $!";
print {$out} "alpha\nbeta\n";
close $out or die "close $path: $!";

open HANDLE, '<', $path or die "open $path: $!";

sub read_from_typeglob_argument {
    my ($input) = @_;
    is reftype(\$input), 'GLOB', 'scalar reference to typeglob reports GLOB';

    my $fh = \$input;
    is $fh->clearerr, 0, 'IO::Handle method dispatch works on scalar ref to glob';
    my @lines = $fh->getlines;
    return join '', @lines;
}

is read_from_typeglob_argument(*HANDLE), "alpha\nbeta\n", 'getlines reads through scalar ref to glob';
ok close(HANDLE), 'closed test handle';

unlink $path;
