use strict;
use warnings;
use Test::More;
use Cwd qw(getcwd);
use File::Path qw(make_path);
use File::Temp qw(tempdir);

my $tmpdir = tempdir(CLEANUP => 1);
my $logical = 'lib/Carp/Assert.pm';
make_path("$tmpdir/lib/Carp") or die "make_path $tmpdir/lib/Carp: $!";

open my $source_fh, '>', "$tmpdir/$logical" or die "open $tmpdir/$logical: $!";
print {$source_fh} "\n" x 9;
print {$source_fh} 'our $TEXT = eval { capture { $foo == $bar } };', "\n";
close $source_fh or die "close $tmpdir/$logical: $!";

open my $runner_fh, '>', "$tmpdir/embedded.t" or die "open $tmpdir/embedded.t: $!";
print {$runner_fh} <<'PERL';
use strict;
use warnings;
use B::Deparse;

our ($TEXT, @CALLER);
sub capture (&) {
    @CALLER = caller(0);
    return B::Deparse->new->coderef2text($_[0]);
}

my $foo = 1;
my $bar = 2;
#line 10 lib/Carp/Assert.pm
$TEXT = eval { capture { $foo == $bar } };
1;
PERL
close $runner_fh or die "close $tmpdir/embedded.t: $!";

my $cwd = getcwd();
chdir $tmpdir or die "chdir $tmpdir: $!";

our ($TEXT, @CALLER);
my $loaded = do './embedded.t';
my $error = $@;

chdir $cwd or die "chdir $cwd: $!";
die $error if $error;
die "do embedded.t: $!" unless defined $loaded;

is($CALLER[1], $logical, 'unquoted #line path preserves slashes');
is($CALLER[2], 10, 'unquoted #line path keeps mapped line number');
like($TEXT, qr/\$foo == \$bar/, 'prototype block source is found through unquoted #line path');

done_testing();
