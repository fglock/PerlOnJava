# Copyright (c) 2001-2014 Robin Houston.
#
# PerlOnJava compatibility subset. The original Want distribution uses XS
# op-tree inspection. The pure-Perl predicates cover common scalar/list use;
# PerlOnJava adds a lazy native call-context bridge for LVALUE and ASSIGN.

package Want;

use strict;
use warnings;
use Config ();
use Exporter 'import';

our $VERSION = '0.29';
our @EXPORT = qw(want rreturn lnoreturn wantref want_ref);

sub want {
    my @wanted = map { split } @_;
    for my $kind (@wanted) {
        my $matches;
        $matches = !defined wantarray if $kind eq 'VOID';
        $matches = defined(wantarray) && wantarray if $kind eq 'LIST';
        $matches = defined(wantarray) && !wantarray if $kind eq 'SCALAR';

        # PerlOnJava's portable fallback has no runtime op tree. OBJECT
        # requires parent-op inspection and is supplied by the Java
        # bridge. RVALUE is the safe fallback outside lvalue calls; BOOL and
        # other parent-op-only predicates remain conservative here.
        $matches = 1 if $kind eq 'RVALUE';
        $matches = 0 if $kind eq 'OBJECT' || $kind eq 'BOOL'
            || $kind eq 'LVALUE' || $kind eq 'ASSIGN';
        return 0 unless $matches;
    }
    return 1;
}

sub rreturn { return wantarray ? @_ : $_[-1] }
sub lnoreturn { return }
sub wantref { return '' }
sub want_ref { return wantref() }

# PerlOnJava keeps the caller's scalar/list/lvalue context on its runtime call
# stack. Load the native bridge only when Want itself is requested; standard
# Perl continues to use the conservative pure-Perl implementation above.
if ($Config::Config{archname} =~ /^java-/) {
    require XSLoader;
    XSLoader::load('Want', $VERSION);
}

1;
