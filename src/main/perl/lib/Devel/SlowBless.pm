package Devel::SlowBless;

use strict;
use warnings;

our $VERSION = '0.06';

require Exporter;
our @ISA = qw(Exporter);
our @EXPORT_OK = qw(amg_gen sub_gen);
our %EXPORT_TAGS = (all => \@EXPORT_OK);

require XSLoader;
XSLoader::load(__PACKAGE__, $VERSION);

my $pid = 0;
my $amg_gen = 0;
my $sub_gen = 0;
my $warn = 0;

sub start_warning { $warn = 1 }
sub stop_warning  { $warn = 0 }

sub DB::DB {
    my $cur_amg = amg_gen();
    my $cur_sub = sub_gen();

    if ($pid != $$) {
        $pid = $$;
        $amg_gen = $cur_amg;
        $sub_gen = $cur_sub;
    }

    require Carp;
    Carp::cluck("[$pid] AMAGIC $amg_gen -> $cur_amg\n")
        if $warn && $amg_gen != $cur_amg;
    Carp::cluck("[$pid] SUB GEN $sub_gen - $cur_sub\n")
        if $warn && $sub_gen != $cur_sub;

    $amg_gen = $cur_amg;
    $sub_gen = $cur_sub;
}

1;
