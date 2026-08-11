use strict;
use warnings;
use Config;
use Cwd qw(getcwd);
use File::Spec;
use File::Temp qw(tempdir);
use Test::More;

my $is_perlonjava = $Config{archname} =~ /^java-/;
if ($is_perlonjava) {
    require CPAN::Distribution;
} else {
    require './src/main/perl/lib/PerlOnJava/Process.pm';
    $INC{'PerlOnJava/Process.pm'} = './src/main/perl/lib/PerlOnJava/Process.pm';
    require './src/main/perl/lib/CPAN/Distribution.pm';
}

{
    package Local::RetryFrontend;
    sub myprint { 1 }
    sub mywarn  { 1 }
}

my $root = tempdir(CLEANUP => 1);
my $original = File::Spec->catfile($root, 'Makefile.PL');
open my $original_fh, '>', $original or die "cannot write $original: $!";
print {$original_fh} <<'ORIGINAL';
die "configure retry was interactive\n"
    unless $ENV{PERL_MM_USE_DEFAULT};
open my $configured, '>', 'configured-by-original' or die $!;
print {$configured} "yes\n";
close $configured;
open my $makefile, '>', 'Makefile' or die $!;
print {$makefile} "all:\n\t\@true\n";
close $makefile;
ORIGINAL
close $original_fh;

my $fallback = File::Spec->catfile($root, '.perlonjava-fallback-Makefile.PL');
open my $fallback_fh, '>', $fallback or die "cannot write $fallback: $!";
print {$fallback_fh}
    'open my $fh, q{>}, q{Makefile} or die $!; '
  . 'print {$fh} "all:\n\t@true\n"; close $fh;' . "\n";
close $fallback_fh;

my $old = getcwd();
chdir $root or die "cannot chdir to $root: $!";
no warnings 'once';
local $CPAN::Frontend = bless {}, 'Local::RetryFrontend';
use warnings 'once';
my $dist = bless {}, 'CPAN::Distribution';
ok($dist->_retry_perlonjava_original_pl_after_prereqs,
   'original configure is retried after fallback prerequisites');
ok(-f 'configured-by-original', 'retry preserves original configure side effects');
ok(-f '.perlonjava-original-pl-retried', 'retry is marked persistently');
ok(!$dist->_retry_perlonjava_original_pl_after_prereqs,
   'original configure retry is bounded to one attempt');
chdir $old or die "cannot restore cwd: $!";

done_testing;
