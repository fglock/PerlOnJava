use strict;
use Test::More tests => 7;

# Note: warnings::enabled() is currently broken - it always returns false
# because warning flags are set at compile time but getCurrentScope() at
# runtime returns a different scope. See dev/design/WARNINGS_RUNTIME_FIX.md
# for details and fix plan.

# Test 1: $SIG{__WARN__} captures warnings from warn()
{
    my $warned = 0;
    local $SIG{__WARN__} = sub { $warned++ };
    warn "test warning";
    is($warned, 1, '$SIG{__WARN__} captures warnings from warn()');
}

# Test 2: substr lvalue assignment beyond string length throws error (not just warning)
{
    use warnings;
    my $str = "Short";
    my $error = 0;
    eval { substr($str, 10, 5) = "long"; };
    $error = 1 if $@ =~ /substr outside of string/;
    is($error, 1, "substr lvalue assignment beyond string length throws error");
}

# Test 3: substr outside of string warning is captured (read with bad offset)
{
    use warnings;
    my $str = "hello";
    my $warned = 0;
    local $SIG{__WARN__} = sub { $warned++ if $_[0] =~ /substr outside of string/ };
    my $val = substr($str, 10, 1);
    is($warned, 1, "substr read beyond string length warns");
}

# Test 4: substr outside of string warning is captured (negative offset too negative)
{
    use warnings;
    my $str = "hello";
    my $warned = 0;
    local $SIG{__WARN__} = sub { $warned++ if $_[0] =~ /substr outside of string/ };
    my $val = substr($str, -10, 1);
    is($warned, 1, "substr read with too-negative offset warns");
}

# Test 5: warning message includes location info
{
    my $msg = '';
    local $SIG{__WARN__} = sub { $msg = $_[0] };
    warn "test";
    like($msg, qr/test.*at.*warnings\.t/, "warning message includes location");
}

# Test 6: warn with newline doesn't add location
{
    my $msg = '';
    local $SIG{__WARN__} = sub { $msg = $_[0] };
    warn "test\n";
    is($msg, "test\n", "warn with newline doesn't add location");
}

# TODO: These tests document broken behavior - warnings::enabled() always returns false
# When the warning system is fixed (see WARNINGS_RUNTIME_FIX.md), these should be enabled:
#
# use warnings;
# ok(warnings::enabled('all'), "'use warnings' enables 'all' category");
# ok(warnings::enabled('substr'), "'use warnings' enables 'substr' category");
# ok(warnings::enabled('numeric'), "'use warnings' enables 'numeric' category");
#
# use warnings 'numeric';
# ok(warnings::enabled('numeric'), "'use warnings \"numeric\"' enables numeric");
#
# use warnings;
# no warnings 'numeric';
# ok(!warnings::enabled('numeric'), "'no warnings \"numeric\"' disables numeric");
# ok(warnings::enabled('substr'), "other categories remain enabled");

# Test 7: BEGIN { unimport warnings 'cat' } inside a sub propagates the
# suppression to runtime. Module::Util uses this idiom to silence
# File::Find's "Can't stat" warnings.
{
    use File::Find;
    sub _find_with_no_warn {
        BEGIN { unimport warnings qw(File::Find) if $] >= 5.008 }
        my @out;
        File::Find::find({ no_chdir => 1, wanted => sub { push @out, $_ } }, $_[0]);
        return @out;
    }

    my @w;
    local $SIG{__WARN__} = sub { push @w, @_ };
    _find_with_no_warn("/no/such/path-for-warnings-test");
    is(scalar(@w), 0, 'BEGIN { unimport warnings ... } inside sub propagates to runtime');
}
