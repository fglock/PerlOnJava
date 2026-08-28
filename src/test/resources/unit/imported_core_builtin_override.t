use strict;
use warnings;
use Test::More;

# Model the way a pure-Perl module exports a subroutine: assigning its CODE
# slot into the caller's package during import must make the imported sub win
# over a same-named core builtin.
BEGIN {
    package ImportedCoreBuiltins;

    sub chmod {
        return 'imported chmod';
    }

    sub lock {
        return 'imported lock';
    }

    sub import {
        my ($class, $caller) = @_;
        $caller //= caller;
        no strict 'refs';
        *{"${caller}::chmod"} = \&chmod;
        *{"${caller}::lock"} = \&lock;
    }

    __PACKAGE__->import('main');
}

is(chmod('+r', 'unused'), 'imported chmod',
    'imported chmod overrides the core builtin');
my $lock_name = 'unused';
is(lock($lock_name), 'imported lock',
    'imported lock overrides the core builtin');
is(CORE::chmod(0, 'unused'), 0,
    'CORE::chmod still selects the core builtin');
my $lock_target = 'unused';
is(CORE::lock($lock_target), 'unused',
    'CORE::lock still selects the core builtin');

done_testing();
