use strict;
use warnings;
use File::Temp qw(tempfile);
use Test::More tests => 3;

my ($handle, $path) = tempfile();
print {$handle} "bar\n";
close $handle;

{
    local $^I = '*';
    local @ARGV = ($path);
    while (<>) {
        print "foo$_";
    }
}

open $handle, '<', $path or die "Cannot read $path: $!";
is(do { local $/; <$handle> }, "foobar\n",
   q{$^I = '*' performs in-place editing without replacing the input by an empty backup});
close $handle;

open $handle, '>', $path or die "Cannot rewrite $path: $!";
print {$handle} "bar\n";
close $handle;

my $runner = $^X eq 'jperl' ? './jperl' : $^X;
my $status = system($runner, '-i', '-n', '-e', 'die', $path);
ok($status != 0, 'an in-place program that dies exits unsuccessfully');

open $handle, '<', $path or die "Cannot read aborted edit $path: $!";
is(do { local $/; <$handle> }, "bar\n",
   'an aborted extensionless in-place edit preserves the original file');
close $handle;
unlink $path;
