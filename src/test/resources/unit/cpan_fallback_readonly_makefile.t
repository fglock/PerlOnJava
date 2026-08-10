use strict;
use warnings;
use Test::More;
use Cwd qw(getcwd);
use File::Temp qw(tempdir);

use CPAN::Distribution;

plan skip_all => 'CPAN::Distribution fallback helper unavailable'
    unless CPAN::Distribution->can('_try_perlonjava_fallback_pl');

{
    package Local::ReadOnlyFallbackDist;
    our @ISA = qw(CPAN::Distribution);
    sub _perlonjava_fallback_pl_args_from_meta_files {
        return { NAME => 'Local::ReadOnlyFallback', VERSION => '0.001' };
    }

    package Local::SilentFrontend;
    sub myprint { return }
}

my $cwd = getcwd();
my $dist_dir = tempdir(CLEANUP => 1);
chdir $dist_dir or die "Could not chdir to $dist_dir: $!";

open my $original, '>', 'Makefile.PL' or die "Could not create Makefile.PL: $!";
print {$original} "# original generated compatibility file\n";
close $original;
chmod 0444, 'Makefile.PL' or die "Could not make Makefile.PL read-only: $!";

my $dist = bless {}, 'Local::ReadOnlyFallbackDist';
local $CPAN::Frontend = bless {}, 'Local::SilentFrontend';
ok($dist->_try_perlonjava_fallback_pl('ignored original command'),
   'fallback succeeds beside a read-only Makefile.PL');
ok(-f 'Makefile', 'fallback script generated a Makefile');
ok(-f '.perlonjava-fallback-Makefile.PL', 'fallback uses a separate script');

open my $unchanged, '<', 'Makefile.PL' or die "Could not reopen Makefile.PL: $!";
is(do { local $/; <$unchanged> }, "# original generated compatibility file\n",
   'fallback preserves the distribution Makefile.PL');
close $unchanged;
chmod 0644, 'Makefile.PL';
chdir $cwd or die "Could not restore working directory to $cwd: $!";

done_testing();
