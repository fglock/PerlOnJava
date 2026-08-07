package AnyDBM_File;

use strict;
use warnings;

our $VERSION = '1.01';
our @ISA = qw(NDBM_File DB_File GDBM_File SDBM_File ODBM_File) unless @ISA;

for my $module (@ISA) {
    if (eval "require $module") {
        @ISA = ($module);
        return 1;
    }
}

die "No DBM package was successfully found or installed";
