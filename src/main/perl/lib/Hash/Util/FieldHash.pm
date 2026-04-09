package Hash::Util::FieldHash;
use strict;
use warnings;
our $VERSION = '1.26';

use Exporter 'import';
our @EXPORT_OK = qw(
    fieldhash fieldhashes
    idhash idhashes
    id id_2obj register
);

# Simplified implementation for PerlOnJava.
#
# In standard Perl, fieldhash() converts a hash to use object identity as keys
# with automatic cleanup on garbage collection (inside-out object pattern).
#
# PerlOnJava's JVM GC handles circular references natively, so the GC-triggered
# cleanup is unnecessary. The hash works as-is with refaddr-based keys -- entries
# just won't auto-clean when objects are destroyed (minor memory leak, functionally
# harmless, consistent with PerlOnJava's weaken() being a no-op).

sub fieldhash (\%) { $_[0] }

sub fieldhashes {
    for (@_) {
        fieldhash(%$_);
    }
    return @_;
}

# idhash is the same concept but without GC magic even in standard Perl
sub idhash (\%) { $_[0] }

sub idhashes {
    for (@_) {
        idhash(%$_);
    }
    return @_;
}

# id() returns the reference address (like Scalar::Util::refaddr)
sub id ($) {
    require Scalar::Util;
    return Scalar::Util::refaddr($_[0]);
}

# id_2obj: returns the object for a given id (not implementable without tracking)
sub id_2obj ($) { return undef }

# register: registers an object for the fieldhash GC mechanism (no-op here)
sub register {
    my ($obj, @hashes) = @_;
    return $obj;
}

1;
