use strict;
use warnings;
use Test::More;
use Scalar::Util qw(weaken);

BEGIN {
    eval { require File::Temp; 1 }
        or plan skip_all => 'File::Temp is not installed';
}

my $tempfile = File::Temp->new();
my $path = $tempfile->filename;
my $weak_tempfile = $tempfile;
weaken($weak_tempfile);

my @stat = stat($tempfile);
ok(@stat, 'stat succeeds on the open tempfile');
ok(defined($weak_tempfile), 'the lexical tempfile remains alive while owned');

undef $tempfile;
ok(!defined($weak_tempfile), 'stat cache does not retain the tempfile object');
ok(!-e $path, 'the tempfile is unlinked when its last Perl owner is released');

done_testing;
