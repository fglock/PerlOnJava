use strict;
use warnings;
use Test::More;
use File::Temp qw(tempfile);

my $seen;
{
    package FileTestCapturedOverload;
    use overload
        fallback => 1,
        -X => sub {
            $seen = [qq($_[0]), $_[1]];
            "-$_[1]";
        };
}

my $object = bless [], 'FileTestCapturedOverload';
is(-r $object, '-r', 'file-test overload returns its result');
is_deeply(
    $seen,
    ["$object", 'r'],
    'file-test overload writes through its captured lexical',
);

our $string_overload_calls = 0;
{
    package FileTestGlobStringOverload;
    use overload
        fallback => 1,
        '""' => sub { ++$main::string_overload_calls; 'not a filehandle' };
}

open my $glob, '<', __FILE__ or die "open __FILE__: $!";
bless $glob, 'FileTestGlobStringOverload';
ok(-r $glob, 'file test follows an open glob without string overloading');
is($string_overload_calls, 0, 'file test does not stringify an open glob');

our $io_string_overload_calls = 0;
{
    package FileTestIOStringOverload;
    use overload
        fallback => 1,
        '""' => sub { ++$main::io_string_overload_calls; 'not a filehandle' };
}

open my $io_fh, '<', __FILE__ or die "open __FILE__ for IO reference: $!";
my $io_ref = *$io_fh{IO};
bless $io_ref, 'FileTestIOStringOverload';
ok(-r $io_ref, 'file test follows a blessed IO reference');
is($io_string_overload_calls, 0, 'file test does not stringify a blessed IO reference');

open my $stat_fh, '<', __FILE__ or die "open __FILE__ for stat: $!";
stat *$stat_fh{IO};
ok(-T _, 'cached text-file test follows stat on an IO handle');

my ($unlinked_fh, $unlinked_path) = tempfile();
print {$unlinked_fh} "plain text\n" or die "write tempfile: $!";
seek($unlinked_fh, 0, 0) or die "seek tempfile: $!";
SKIP: {
    skip 'open file deletion is unsupported', 1 unless unlink $unlinked_path;
    stat *$unlinked_fh{IO};
    ok(-T _, 'cached text-file test reads an unlinked open file');
}

done_testing();
