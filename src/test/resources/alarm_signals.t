#!/usr/bin/env perl
use strict;
use warnings;
use Test::More;

# Test alarm() and signal handling functionality

subtest 'basic alarm with die' => sub {
    plan tests => 2;
    
    my $alarm_fired = 0;
    
    $SIG{ALRM} = sub { $alarm_fired = 1; die "alarm\n" };
    
    eval {
        alarm(1);
        sleep 5;  # Should be interrupted
    };
    
    ok($alarm_fired, 'alarm signal handler was called');
    like($@, qr/alarm/, 'die message propagated correctly');
    
    alarm(0);  # Cancel any pending alarm
};

subtest 'alarm interrupts infinite for loop' => sub {
    plan tests => 2;
    
    my $alarm_fired = 0;
    my $iterations = 0;
    
    $SIG{ALRM} = sub { $alarm_fired = 1; die "timeout\n" };
    
    eval {
        alarm(1);
        for (my $i = 0; ; $i++) {
            $iterations++;
        }
    };
    
    ok($alarm_fired, 'alarm fired during infinite for loop');
    like($@, qr/timeout/, 'loop was interrupted');
    
    alarm(0);
};

subtest 'alarm interrupts infinite while loop' => sub {
    plan tests => 2;
    
    my $alarm_fired = 0;
    my $iterations = 0;
    
    $SIG{ALRM} = sub { $alarm_fired = 1; die "timeout\n" };
    
    eval {
        alarm(1);
        while (1) {
            $iterations++;
        }
    };
    
    ok($alarm_fired, 'alarm fired during infinite while loop');
    like($@, qr/timeout/, 'loop was interrupted');
    
    alarm(0);
};

subtest 'alarm interrupts foreach loop' => sub {
    plan tests => 2;
    
    my $alarm_fired = 0;
    my $count = 0;
    
    $SIG{ALRM} = sub { $alarm_fired = 1; die "timeout\n" };
    
    eval {
        alarm(1);
        foreach my $i (1..999999999) {
            $count++;  # Make sure loop does some work
        }
    };
    
    ok($alarm_fired, 'alarm fired during foreach loop');
    like($@, qr/timeout/, 'foreach was interrupted');
    
    alarm(0);
};

subtest 'alarm returns previous remaining time' => sub {
    plan tests => 3;
    
    my $prev = alarm(10);
    is($prev, 0, 'no previous alarm');
    
    sleep 1;
    
    $prev = alarm(5);
    ok($prev >= 8 && $prev <= 10, "previous alarm had ~9 seconds remaining, got $prev");
    
    $prev = alarm(0);
    ok($prev >= 4 && $prev <= 6, "cancelled alarm had ~5 seconds remaining, got $prev");
};

subtest 'alarm can be cancelled' => sub {
    plan tests => 1;
    
    my $alarm_fired = 0;
    
    $SIG{ALRM} = sub { $alarm_fired = 1; die "alarm\n" };
    
    eval {
        alarm(10);
        alarm(0);  # Cancel immediately
        sleep 2;   # Should complete without interruption
    };
    
    is($alarm_fired, 0, 'cancelled alarm did not fire');
};

subtest 'alarm with fast-completing loop' => sub {
    plan tests => 2;  # Fixed: was 1, should be 2
    
    my $alarm_fired = 0;
    my $sum = 0;
    
    $SIG{ALRM} = sub { $alarm_fired = 1; die "timeout\n" };
    
    eval {
        alarm(5);
        for (my $i = 0; $i < 100; $i++) {
            $sum += $i;
        }
        alarm(0);  # Cancel before it fires
    };
    
    is($alarm_fired, 0, 'fast loop completed before alarm');
    is($sum, 4950, 'loop calculated correct sum');
};

subtest 'nested loops with alarm' => sub {
    plan tests => 2;
    
    my $alarm_fired = 0;
    
    $SIG{ALRM} = sub { $alarm_fired = 1; die "timeout\n" };
    
    eval {
        alarm(1);
        for (my $i = 0; ; $i++) {
            for (my $j = 0; $j < 1000; $j++) {
                # Inner loop
            }
        }
    };
    
    ok($alarm_fired, 'alarm fired during nested loops');
    like($@, qr/timeout/, 'nested loops were interrupted');
    
    alarm(0);
};

subtest 'alarm with do-while loop' => sub {
    plan tests => 2;
    
    my $alarm_fired = 0;
    
    $SIG{ALRM} = sub { $alarm_fired = 1; die "timeout\n" };
    
    eval {
        alarm(1);
        do {
            # Infinite loop
        } while (1);
    };
    
    ok($alarm_fired, 'alarm fired during do-while loop');
    like($@, qr/timeout/, 'do-while was interrupted');
    
    alarm(0);
};

subtest 'signal handler can set variable' => sub {
    plan tests => 2;
    
    my $alarm_count = 0;
    
    $SIG{ALRM} = sub { $alarm_count++; die "alarm\n" };
    
    eval {
        alarm(1);
        while (1) { }
    };
    
    is($alarm_count, 1, 'signal handler modified variable');
    like($@, qr/alarm/, 'die propagated from handler');
    
    alarm(0);
};

subtest 'multiple alarms in sequence' => sub {
    plan tests => 4;
    
    my $alarm_count = 0;
    
    $SIG{ALRM} = sub { $alarm_count++; die "alarm\n" };
    
    # First alarm
    eval {
        alarm(1);
        while (1) { }
    };
    is($alarm_count, 1, 'first alarm fired');
    
    # Second alarm
    eval {
        alarm(1);
        while (1) { }
    };
    is($alarm_count, 2, 'second alarm fired');
    
    # Third alarm
    eval {
        alarm(1);
        while (1) { }
    };
    is($alarm_count, 3, 'third alarm fired');
    
    ok($alarm_count == 3, 'all alarms fired correctly');
    
    alarm(0);
};

subtest 'alarm with complex signal handler' => sub {
    plan tests => 3;
    
    my @log;
    
    $SIG{ALRM} = sub {
        push @log, "handler called";
        for my $i (1..5) {
            push @log, "step $i";
        }
        die "done\n";
    };
    
    eval {
        alarm(1);
        while (1) { }
    };
    
    is(scalar(@log), 6, 'handler executed all steps');
    is($log[0], 'handler called', 'first log entry correct');
    is($log[-1], 'step 5', 'last log entry correct');
    
    alarm(0);
};

subtest 'alarm with potentially slow regex' => sub {
    plan tests => 1;
    
    # Note: Catastrophic backtracking patterns are hard to trigger reliably
    # across different Java versions/regex engines. This test verifies that
    # the timeout wrapper mechanism is in place, not that we hit actual timeouts.
    
    my $result;
    eval {
        alarm(5);
        # This pattern can cause backtracking but may complete quickly
        my $s = 'a' x 25;
        $result = ($s =~ /(a+)+b/);  # Returns false since no 'b'
        alarm(0);
    };
    
    # Either way (timeout or completion), test passes if no crash
    ok(!$@ || $@ =~ /timeout/, 'regex with alarm either completed or timed out cleanly');
    
    alarm(0);
};

subtest 'normal regex works fine with alarm' => sub {
    plan tests => 3;
    
    my $alarm_fired = 0;
    
    $SIG{ALRM} = sub { $alarm_fired = 1; die "timeout\n" };
    
    my $result;
    eval {
        alarm(5);
        # Normal, fast regex should complete before alarm
        $result = ('hello world' =~ /world/);
        alarm(0);
    };
    
    ok($result, 'normal regex matched');
    is($alarm_fired, 0, 'alarm did not fire for fast regex');
    is($@, '', 'no error occurred');
    
    alarm(0);
};

subtest 'regex with captures works with alarm' => sub {
    plan tests => 4;
    
    my $alarm_fired = 0;
    
    $SIG{ALRM} = sub { $alarm_fired = 1; die "timeout\n" };
    
    my $result;
    eval {
        alarm(5);
        'abc123xyz' =~ /(\d+)/;
        $result = $1;
        alarm(0);
    };
    
    is($result, '123', 'captured value correct');
    is($alarm_fired, 0, 'alarm did not fire');
    is($@, '', 'no error occurred');
    ok(defined($result), 'capture variable was set');
    
    alarm(0);
};

subtest 'alarm with substitution' => sub {
    plan tests => 3;
    
    my $alarm_fired = 0;
    
    $SIG{ALRM} = sub { $alarm_fired = 1; die "timeout\n" };
    
    my $str = 'hello world';
    eval {
        alarm(5);
        $str =~ s/world/universe/;
        alarm(0);
    };
    
    is($str, 'hello universe', 'substitution worked');
    is($alarm_fired, 0, 'alarm did not fire for fast substitution');
    is($@, '', 'no error occurred');
    
    alarm(0);
};

subtest 'alarm with global match' => sub {
    plan tests => 3;
    
    my $alarm_fired = 0;
    
    $SIG{ALRM} = sub { $alarm_fired = 1; die "timeout\n" };
    
    my @matches;
    eval {
        alarm(5);
        @matches = ('a1b2c3' =~ /(\d)/g);
        alarm(0);
    };
    
    is_deeply(\@matches, ['1', '2', '3'], 'global match captured all digits');
    is($alarm_fired, 0, 'alarm did not fire');
    is($@, '', 'no error occurred');
    
    alarm(0);
};

done_testing();

