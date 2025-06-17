use 5.34.0;
use strict;
use warnings;
use Test::More;

# Test die
sub test_die {
    my $result;
    local $SIG{__DIE__} = sub {
        $result = "Caught die: @_";
    };
    eval {
        die "This is a die test";
    };
    return $result;
}

# Test warn
sub test_warn {
    my $result;
    local $SIG{__WARN__} = sub {
        $result = "Caught warn: @_";
    };
    warn "This is a warn test";
    return $result;
}

# Test $SIG{__DIE__}
sub test_sig_die {
    my $result;
    local $SIG{__DIE__} = sub {
        $result = "Caught SIG DIE: @_";
    };
    eval {
        die "This is a SIG DIE test";
    };
    return $result;
}

# Test $SIG{__WARN__}
sub test_sig_warn {
    my $result;
    local $SIG{__WARN__} = sub {
        $result = "Caught SIG WARN: @_";
    };
    warn "This is a SIG WARN test";
    return $result;
}

# Run tests
my $die_result = test_die();
like($die_result, qr/Caught die: This is a die test/, 'die handler works');

my $warn_result = test_warn();
like($warn_result, qr/Caught warn: This is a warn test/, 'warn handler works');

my $sig_die_result = test_sig_die();
like($sig_die_result, qr/Caught SIG DIE: This is a SIG DIE test/, 'SIG DIE handler works');

my $sig_warn_result = test_sig_warn();
like($sig_warn_result, qr/Caught SIG WARN: This is a SIG WARN test/, 'SIG WARN handler works');

done_testing();
