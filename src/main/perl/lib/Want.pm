# Copyright (c) 2001-2014 Robin Houston.
#
# PerlOnJava compatibility subset.  The original Want distribution uses XS
# op-tree inspection.  These predicates cover the non-lvalue call patterns
# used by JSONP without pretending to provide full Want parity.

package Want;

use strict;
use warnings;
use Exporter 'import';

our $VERSION = '0.29';
our @EXPORT = qw(want rreturn lnoreturn wantref want_ref);

sub want {
    my ($kind) = @_;
    return !defined wantarray if $kind eq 'VOID';
    return defined(wantarray) && wantarray if $kind eq 'LIST';
    return defined(wantarray) && !wantarray if $kind eq 'SCALAR';

    # PerlOnJava currently has no runtime op tree.  Method chaining is the
    # useful OBJECT case supported by this compatibility layer.  RVALUE is
    # the safe default outside lvalue calls; BOOL remains conservative.
    return 1 if $kind eq 'OBJECT';
    return 1 if $kind eq 'RVALUE';
    return 0 if $kind eq 'BOOL';
    return 0 if $kind eq 'LVALUE' || $kind eq 'ASSIGN';
    return 0;
}

sub rreturn { return wantarray ? @_ : $_[-1] }
sub lnoreturn { return }
sub wantref { return '' }
sub want_ref { return wantref() }

1;
