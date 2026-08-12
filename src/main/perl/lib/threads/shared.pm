package threads::shared;

use strict;
use warnings;

our $VERSION = '1.69';
our @EXPORT = qw(share is_shared shared_clone);

sub share (\[$@%]) { return _share($_[0]) }
sub is_shared { return _is_shared($_[0]) }
sub shared_clone { return _shared_clone($_[0]) }

sub import {
    my $caller = caller;
    no strict 'refs';
    for my $name (@EXPORT) {
        *{"${caller}::$name"} = \&{$name};
    }
}

1;
