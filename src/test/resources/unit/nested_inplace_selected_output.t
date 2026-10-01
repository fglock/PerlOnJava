use strict;
use warnings;
use Test::More tests => 4;
use File::Temp qw(tempdir);

my $dir = tempdir(CLEANUP => 1);
my @outer = map { "$dir/outer$_" } 1..2;
my @inner = map { "$dir/inner$_" } 1..2;
for my $path (@outer, @inner) {
    open my $fh, '>', $path or die $!;
    print {$fh} "original\n";
    close $fh;
}
sub edit_inner {
    local *ARGV;
    local *ARGVOUT;
    local $_;
    @ARGV = @inner;
    while (<>) {
        s/^/inner:/;
        print;
    }
}
{
    local *ARGV;
    local $^I = '.bak';
    local $_;
    @ARGV = @outer;
    my $first = 1;
    while (<>) {
        edit_inner() if $first--;
        s/^/outer:/;
        print;
    }
}
for my $path (@outer, @inner) {
    open my $fh, '<', $path or die $!;
    my $line = <$fh>;
    close $fh;
    my $expected = $path =~ /outer/ ? "outer:original\n" : "inner:original\n";
    is $line, $expected, 'nested in-place traversal writes the owning output file';
}
