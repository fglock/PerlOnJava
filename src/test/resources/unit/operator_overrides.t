#!/usr/bin/env perl
use strict;
use warnings;
use Test::More;
use File::Temp qw(tempfile);

# Test operator overrides with different operators to avoid conflicts
# since once an operator is overridden, it can't be switched back

subtest 'local override with use subs' => sub {
    plan tests => 2;

    # Test hex operator override
    {
        use subs 'hex';
        sub hex {
            my $arg = shift // '';
            return 999;
        }

        is(hex("123"), 999, 'hex overridden locally with use subs');
        is(CORE::hex("10"), 16, 'CORE::hex still works');
    }
};

subtest 'global override with CORE::GLOBAL' => sub {
    plan tests => 2;

    # Test oct operator override
    BEGIN {
        *CORE::GLOBAL::oct = sub {
            my $arg = shift // '';
            return 888;
        };
    }

    is(oct("123"), 888, 'oct overridden globally');
    is(CORE::oct("10"), 8, 'CORE::oct still works');
};

subtest 'do operator special case' => sub {
    plan tests => 3;

    # Test that do BLOCK and do EXPR are handled differently
    use subs 'do';
    sub do {
        my $arg = shift // '';
        return "do_override_called";
    }

    # do EXPR should call the override
    is(do("test"), "do_override_called", 'do EXPR calls override');

    # do BLOCK should not call the override
    my $result = do { "do_block_result" };
    is($result, "do_block_result", 'do BLOCK does not call override');

    # CORE::do should work normally
    # Use File::Temp for CORE::do test
    my ($fh, $tmpfile) = tempfile(SUFFIX => '.pl', UNLINK => 1);
    print $fh '"core_do_result"';
    close $fh;

    my $core_result = CORE::do($tmpfile) or diag("CORE::do failed for '$tmpfile': $! $@");
    is($core_result, "core_do_result", 'CORE::do works normally');
};

subtest 'warn operator override' => sub {
    plan tests => 2;

    # Capture warnings
    our @warnings;

    # Override warn globally
    BEGIN {
        *CORE::GLOBAL::warn = sub {
            push @warnings, join('', @_);
        };
    }

    # Test overridden warn
    warn "test warning\n";
    is(scalar(@warnings), 1, 'warn override captured one warning');
    is($warnings[0], "test warning\n", 'warn override captured correct message');
};

subtest 'rename operator override' => sub {
    plan tests => 2;

    # Override rename with use subs
    use subs 'rename';
    sub rename {
        return "fake_rename";
    }

    is(rename(), "fake_rename", 'rename overridden with use subs');
    # CORE::rename would actually rename files, so just check it's callable
    ok(defined &CORE::rename, 'CORE::rename exists');
};

subtest 'uc operator override' => sub {
    plan tests => 3;

    # Override uc globally
    BEGIN {
        *CORE::GLOBAL::uc = sub {
            my $str = shift // '';
            return "UC[$str]";
        };
    }

    is(uc("hello"), "UC[hello]", 'uc overridden globally');
    is(uc("world"), "UC[world]", 'uc override works with different input');
    is(CORE::uc("test"), "TEST", 'CORE::uc still works');
};

subtest 'caller operator override' => sub {
    plan tests => 2;

    # Override caller with use subs
    use subs 'caller';
    sub caller {
        my $level = shift // 0;
        return ("OverriddenPackage", "override.pl", 42);
    }

    my ($package, $file, $line) = caller();
    is($package, "OverriddenPackage", 'caller override returns custom package');
    is($line, 42, 'caller override returns custom line number');
};

subtest 'multiple overrides precedence' => sub {
    plan tests => 2;

    # When both local and global overrides exist, local takes precedence
    BEGIN {
        *CORE::GLOBAL::stat = sub {
            return "global_stat";
        };
    }

    {
        use subs 'stat';
        sub stat {
            return "local_stat";
        }

        is(stat("dummy"), "local_stat", 'local override takes precedence over global');
    }

    # Outside the block, local override is still used
    is(stat("dummy"), "local_stat", 'global override used when no local override');
};

subtest 'die operator override' => sub {
    plan tests => 2;

    # Override die with use subs
    use subs 'die';
    sub die {
        my $msg = shift // '';
        # Instead of dying, just return a special value
        return "DIED: $msg";
    }

    my $result = eval { die "test error" };
    is($@, '', 'die override prevented actual death');
    is($result, "DIED: test error", 'die override returned custom value');
};

subtest 'sleep operator override' => sub {
    plan tests => 3;

    # Override sleep globally so it doesn't actually wait.
    # Required for Test::MockTime::HiRes-style mocking.
    our @sleep_args;
    BEGIN {
        *CORE::GLOBAL::sleep = sub {
            push @sleep_args, $_[0];
            return $_[0];
        };
    }

    my $start = time;
    my $rc = sleep 5;
    my $elapsed = time - $start;
    is($rc, 5, 'sleep override returned the requested duration');
    cmp_ok($elapsed, '<', 2, 'sleep override did not actually wait');
    is_deeply(\@sleep_args, [5], 'sleep override saw the right argument');
};

subtest 'gethostbyname operator override' => sub {
    plan tests => 2;

    BEGIN {
        *CORE::GLOBAL::gethostbyname = sub {
            die "unexpected list context" if wantarray;
            return "mocked:$_[0]";
        };
    }

    is(gethostbyname("www.perl.org."), "mocked:www.perl.org.",
       'gethostbyname overridden globally');
    ok(defined CORE::gethostbyname("localhost"),
       'CORE::gethostbyname still bypasses override');
};

subtest 'time family CORE::GLOBAL overrides' => sub {
    plan tests => 8;

    BEGIN {
        *CORE::GLOBAL::localtime = sub (;$) {
            return wantarray ? ('local-list', $_[0] // 'undef') : 'local-scalar:' . ($_[0] // 'undef');
        };
        *CORE::GLOBAL::gmtime = sub (;$) {
            return wantarray ? ('gm-list', $_[0] // 'undef') : 'gm-scalar:' . ($_[0] // 'undef');
        };
    }

    is(scalar(localtime), 'local-scalar:undef', 'localtime override works without args');
    is(scalar(localtime 123), 'local-scalar:123', 'localtime override works with an arg');
    is_deeply([ localtime 456 ], [ 'local-list', 456 ], 'localtime override preserves list context');

    my @values = (0, 1, 2);
    is(scalar(localtime @values), 'local-scalar:3', 'localtime override applies unary prototype');

    is(scalar(gmtime), 'gm-scalar:undef', 'gmtime override works without args');
    is(scalar(gmtime 789), 'gm-scalar:789', 'gmtime override works with an arg');
    is_deeply([ gmtime 987 ], [ 'gm-list', 987 ], 'gmtime override preserves list context');
    is(scalar(gmtime @values), 'gm-scalar:3', 'gmtime override applies unary prototype');
};

done_testing();
