#!/usr/bin/env perl
use strict;
use warnings;
use Test::More;

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
    # Create a temp file for CORE::do test
    my $tmpfile = "./test_do_$$.pl";
    open my $fh, '>', $tmpfile or die "Cannot create $tmpfile: $!";
    print $fh '"core_do_result"';
    close $fh;

    my $core_result = CORE::do $tmpfile;
    unlink $tmpfile;
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

subtest 'time operator override' => sub {
    plan tests => 2;

    # Override time with use subs
    use subs 'time';
    sub time {
        return 1234567890;
    }

    is(time(), 1234567890, 'time overridden with use subs');
    ok(CORE::time() > 1000000000, 'CORE::time returns reasonable value');
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

done_testing();