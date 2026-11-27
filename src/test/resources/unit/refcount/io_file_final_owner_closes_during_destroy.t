use strict;
use warnings;
use File::Temp qw(tempfile);
use IO::File;
use Scalar::Util qw(weaken unweaken);
use Test::More;

{
    package TrackedIOFile;
    our @ISA = ('IO::File');
    our $destroyed = 0;
    our $fileno_during_destroy;

    sub DESTROY {
        my ($self) = @_;
        ++$destroyed;
        $fileno_during_destroy = defined CORE::fileno($self) ? 1 : 0;
        IO::Handle::DESTROY($self);
    }
}

my ($temporary, $path) = tempfile();
close $temporary or die "close temporary file: $!";

sub return_tracked_file {
    my ($file) = @_;
    my $handle = TrackedIOFile->new($file, '>')
        or die "open returned tracked handle: $!";
    return $handle;
}

{
    my $handle = TrackedIOFile->new($path, '>')
        or die "open tracked handle: $!";
    ok(defined fileno($handle), 'IO::File has a live descriptor in its scope');
}

is($TrackedIOFile::destroyed, 1, 'final Perl owner runs IO::File DESTROY once');
is($TrackedIOFile::fileno_during_destroy, 1,
   'descriptor remains registered until IO::File DESTROY closes it');

{
    my $handle = TrackedIOFile->new($path, '>')
        or die "open aliased tracked handle: $!";
    my $alias = $handle;
    ok(defined fileno($alias), 'a scalar alias shares the live IO::File handle');
}

is($TrackedIOFile::destroyed, 2,
   'the final scalar alias runs IO::File DESTROY once');
is($TrackedIOFile::fileno_during_destroy, 1,
   'the final scalar alias keeps its descriptor registered through DESTROY');

{
    my $returned = return_tracked_file($path);
    is($TrackedIOFile::destroyed, 2,
       'scalar return keeps the IO::File object alive');
    ok(defined fileno($returned), 'scalar return keeps the file descriptor available');
}

is($TrackedIOFile::destroyed, 3, 'scalar return releases its final file owner at scope exit');
is($TrackedIOFile::fileno_during_destroy, 1,
   'scalar return keeps its descriptor registered through DESTROY');

{
    my $handle = TrackedIOFile->new($path, '>')
        or die "open array-held tracked handle: $!";
    my $array = [$handle];
    ok(defined fileno($array->[0]), 'array element keeps the IO::File descriptor available');
}

is($TrackedIOFile::destroyed, 4, 'array element release runs IO::File DESTROY once');
is($TrackedIOFile::fileno_during_destroy, 1,
   'array element keeps the descriptor registered through DESTROY');

{
    my $handle = TrackedIOFile->new($path, '>')
        or die "open hash-held tracked handle: $!";
    my $hash = { handle => $handle };
    ok(defined fileno($hash->{handle}), 'hash element keeps the IO::File descriptor available');
}

is($TrackedIOFile::destroyed, 5, 'hash element release runs IO::File DESTROY once');
is($TrackedIOFile::fileno_during_destroy, 1,
   'hash element keeps the descriptor registered through DESTROY');

my $weak;
{
    my $strong = TrackedIOFile->new($path, '>')
        or die "open weakly observed tracked handle: $!";
    $weak = $strong;
    weaken($weak);
    ok(defined fileno($weak), 'weak observer reads the live IO::File descriptor');
}

ok(!defined($weak), 'weak observer clears when the last strong IO::File owner leaves');
is($TrackedIOFile::destroyed, 6, 'weak observer does not postpone IO::File DESTROY');
is($TrackedIOFile::fileno_during_destroy, 1,
   'weak observer does not unregister the descriptor before DESTROY');

{
    my $promoted;
    {
        my $strong = TrackedIOFile->new($path, '>')
            or die "open unweakened tracked handle: $!";
        $promoted = $strong;
        weaken($promoted);
        unweaken($promoted);
        ok(defined fileno($promoted), 'unweaken restores a strong IO::File owner');
    }
    is($TrackedIOFile::destroyed, 6,
       'unweakened owner survives the original scalar scope');
    ok(defined fileno($promoted), 'unweakened owner keeps its descriptor available');
}

is($TrackedIOFile::destroyed, 7, 'unweakened owner runs DESTROY at its own scope exit');
is($TrackedIOFile::fileno_during_destroy, 1,
   'unweakened owner keeps its descriptor registered through DESTROY');

unlink $path or die "unlink temporary file: $!";
done_testing();
