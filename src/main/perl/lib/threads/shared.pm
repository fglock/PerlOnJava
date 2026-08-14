package threads::shared;

use strict;
use warnings;

our $VERSION = '1.69';
our @EXPORT = qw(share is_shared shared_clone cond_wait cond_timedwait
                 cond_signal cond_broadcast);

sub share (\[$@%]) { return _share($_[0]) }
sub is_shared { return _is_shared($_[0]) || _is_shared(\$_[0]) }
sub shared_clone { return _shared_clone($_[0]) }
sub cond_wait (\[$@%];\[$@%]) { return _cond_wait(@_) }
sub cond_timedwait (\[$@%]$;\[$@%]) { return _cond_timedwait(@_) }
sub cond_signal (\[$@%]) { return _cond_signal($_[0]) }
sub cond_broadcast (\[$@%]) { return _cond_broadcast($_[0]) }

sub import {
    my $caller = caller;
    no strict 'refs';
    for my $name (@EXPORT) {
        *{"${caller}::$name"} = \&{$name};
    }
}

1;
