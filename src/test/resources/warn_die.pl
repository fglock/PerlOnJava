use 5.34.0;
use strict;
use warnings;
use feature 'say';

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
print "not " if $die_result !~ "Caught die: This is a die test"; say "ok # die works";

my $warn_result = test_warn();
print "not " if $warn_result !~ "Caught warn: This is a warn test"; say "ok # warn works";

my $sig_die_result = test_sig_die();
print "not " if $sig_die_result !~ "Caught SIG DIE: This is a SIG DIE test"; say "ok # SIG DIE works";

my $sig_warn_result = test_sig_warn();
print "not " if $sig_warn_result !~ "Caught SIG WARN: This is a SIG WARN test"; say "ok # SIG WARN works";

# Reset $SIG because we going to execute other test files in the same process
%SIG = ();
