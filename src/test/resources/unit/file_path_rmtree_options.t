use strict;
use warnings;

use File::Path qw(mkpath remove_tree rmtree);
use File::Temp qw(tempdir);
use Test::More tests => 11;

my $temporary = tempdir(CLEANUP => 1);
my $root = "$temporary/root";
my $child = "$root/child";
my $file = "$child/file";

mkpath($child);
open my $fh, '>', $file or die "open $file: $!";
print {$fh} "contents\n";
close $fh or die "close $file: $!";

my ($result, $errors);
my $removed = rmtree($root, {
    keep_root => 1,
    result    => \$result,
    error     => \$errors,
});

is($removed, 2, 'rmtree counts removed descendants with keep_root');
ok(-d $root, 'rmtree keeps the root with modern options');
ok(!-e $child, 'rmtree removes child directory');
is_deeply($errors, [], 'rmtree reports no errors');
is(scalar @$result, 2, 'rmtree records removed descendants');

my $legacy = "$temporary/legacy";
mkpath($legacy);
is(rmtree($legacy, 0, 0), 1, 'legacy positional rmtree arguments still work');
ok(!-e $legacy, 'legacy rmtree removes its root');

my $quiet = "$temporary/quiet";
mkpath($quiet);
my $output = '';
{
    open my $capture, '>', \$output or die "open capture: $!";
    local *STDOUT = $capture;
    remove_tree($quiet, { keep_root => 1 });
}
is($output, '', 'modern option hash does not enable verbose output');
ok(-d $quiet, 'remove_tree keeps root with modern options');
is(remove_tree($quiet), 1, 'remove_tree removes retained root');
ok(!-e $quiet, 'retained root cleanup succeeds');
