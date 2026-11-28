use strict;
use warnings;
use Test::More;
use File::Temp qw(tempfile);

my ($writer, $path) = tempfile();
print {$writer} "foo\nbar\n" or die "write $path: $!";
close $writer or die "close $path: $!";

my ($taint_writer, $taint_path) = tempfile();
print {$taint_writer} "taint\n" or die "write $taint_path: $!";
my $taint_status = system($^X, '-T', '-e', 'exit 0');
is($taint_status, 0, 'taint-mode subprocess exits successfully');
close $taint_writer or die "close $taint_path: $!";
unlink $taint_path or die "unlink $taint_path: $!";

my $status = system($^X, '-i', '-p', '-e', 's/foo/quux/', $path);
is($status, 0, 'in-place editing subprocess exits successfully');

open my $reader, '<', $path or die "read $path: $!";
my $contents = do { local $/; <$reader> };
close $reader or die "close $path: $!";
is($contents, "quux\nbar\n", 'in-place editing leaves a readable file');

{
    local @ARGV = ($path);
    is(scalar <>, "quux\n", 'readline opens and reads the first ARGV file');
    is(scalar <>, "bar\n", 'readline advances through the ARGV file');
    is(scalar <>, undef, 'readline reports EOF for the ARGV file');
}

my $code = '42; 1';
local @INC = (sub {
    return unless $_[1] eq '/eval_do';
    open my $fh, '<', \$code or die "open in-memory source: $!";
    return $fh;
}, @INC);
my ($result, $warning);
{
    local $^W;
    local $SIG{__WARN__} = sub { $warning .= shift };
    $result = do '/eval_do';
}
is($result, 1, 'do reads an in-memory filehandle without losing its owner');
is($warning, undef, 'do from an in-memory filehandle does not propagate warnings');

done_testing;
