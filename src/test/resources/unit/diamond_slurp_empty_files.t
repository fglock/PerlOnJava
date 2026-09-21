use strict;
use warnings;
use Test::More tests => 3;

my $dir = "diamond_slurp_empty_$$";
mkdir $dir or die "mkdir $dir: $!";
END { unlink glob "$dir/*"; rmdir $dir; }

for my $name (qw(first second)) {
    open my $fh, '>', "$dir/$name" or die "open $name: $!";
    close $fh or die "close $name: $!";
}

{
    local @ARGV = ("$dir/first", "$dir/second");
    local $^I = '.bak';
    local $/;
    my $records = 0;
    while (<>) {
        ++$records;
        print "record $records\n";
    }
    is($records, 2, 'slurp diamond visits each empty input file');
}

for my $name (qw(first second)) {
    open my $fh, '<', "$dir/$name" or die "read $name: $!";
    is(<$fh>, "record " . ($name eq 'first' ? 1 : 2) . "\n",
       "in-place output is written for $name");
    close $fh;
}
