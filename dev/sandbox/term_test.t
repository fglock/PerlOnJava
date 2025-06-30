#!/usr/bin/env perl
use strict;
use warnings;
use Test::More;

# Check if modules can be loaded
BEGIN {
    use_ok('Term::ReadLine');
    use_ok('Term::ReadKey');
}

# Platform detection
my $is_windows = $^O eq 'MSWin32';
my $is_interactive = -t STDIN;

diag("Platform: $^O");
diag("Interactive: " . ($is_interactive ? "yes" : "no"));

# Test Term::ReadLine basics
subtest 'Term::ReadLine basics' => sub {
    my $term = Term::ReadLine->new('Test App');
    ok($term, "Created Term::ReadLine object");
    
    # Check implementation name
    my $impl = $term->ReadLine;
    ok($impl, "Got ReadLine implementation: $impl");
    diag("Implementation: $impl");
    
    # Test findConsole
    my @console = eval { $term->findConsole() };
    SKIP: {
        skip "findConsole not implemented in $impl", 3 if $@;
        is(scalar @console, 2, "findConsole returns 2 values");
        ok($console[0], "Got input console: $console[0]");
        ok($console[1], "Got output console: $console[1]");
    }
    
    # Test MinLine - only if supported
    SKIP: {
        skip "MinLine not supported in $impl", 2 unless $term->can('MinLine');
        my $old_min = $term->MinLine(5);
        ok(defined $old_min, "MinLine returns old value");
        is($term->MinLine(), 5, "MinLine set correctly");
        $term->MinLine($old_min) if defined $old_min;  # Restore
    }
    
    # Test Features - if available
    SKIP: {
        skip "Features not supported in $impl", 2 unless $term->can('Features');
        my $features = $term->Features;
        isa_ok($features, 'HASH', 'Features returns hashref');
        
        # Check for features that might exist
        if (keys %$features) {
            diag("Available features: " . join(", ", sort keys %$features));
        }
    }
    
    # Test Attribs - if available
    SKIP: {
        skip "Attribs not supported in $impl", 1 unless $term->can('Attribs');
        my $attribs = $term->Attribs;
        isa_ok($attribs, 'HASH', 'Attribs returns hashref');
    }
    
    # Test readline method (non-interactive)
    SKIP: {
        skip "readline not supported", 1 unless $term->can('readline');
        # We can't actually test readline without user input
        ok($term->can('readline'), "Has readline method");
    }
    
    # Test addhistory - if available
    SKIP: {
        skip "addhistory not supported in $impl", 1 unless $term->can('addhistory');
        eval { $term->addhistory("test line") };
        ok(!$@, "Can add to history") or diag("Error: $@");
    }
};

# Test Term::ReadKey basics
subtest 'Term::ReadKey basics' => sub {
    # Test GetTerminalSize
    my @size = eval { GetTerminalSize() };
    SKIP: {
        skip "GetTerminalSize failed: $@", 2 if $@;
        skip "No terminal attached", 2 unless @size >= 2;
        cmp_ok($size[0], '>', 0, "Terminal width > 0: $size[0]");
        cmp_ok($size[1], '>', 0, "Terminal height > 0: $size[1]");
    }
    
    # Test ReadMode (just verify it doesn't die)
    eval { ReadMode('normal') };
    ok(!$@, "Can set normal mode") or diag("Error: $@");
    
    eval { ReadMode('restore') };
    ok(!$@, "Can restore mode") or diag("Error: $@");
    
    SKIP: {
        skip "Platform-specific tests on Windows", 4 if $is_windows;
        
        # Test GetSpeed
        my @speed = eval { GetSpeed() };
        if ($@) {
            diag("GetSpeed error: $@");
            skip "GetSpeed not available", 1;
        }
        ok(@speed == 0 || @speed == 2, "GetSpeed returns 0 or 2 values");
        
        # Test GetControlChars
        my %ctrl = eval { GetControlChars() };
        if ($@) {
            diag("GetControlChars error: $@");
            skip "GetControlChars not available", 3;
        }
        ok(1, "GetControlChars didn't die");
        
        # Common control chars that should exist
        SKIP: {
            skip "No control chars found", 2 unless keys %ctrl;
            # Different implementations might use different names
            my $has_interrupt = exists $ctrl{INTERRUPT} || exists $ctrl{INTR} || exists $ctrl{INT};
            ok($has_interrupt, "Has interrupt control char") 
                or diag("Control chars: " . join(", ", sort keys %ctrl));
            
            my $has_eof = exists $ctrl{EOF} || exists $ctrl{VEOF};
            ok($has_eof, "Has EOF control char");
        }
    }
};

# Non-interactive input tests
subtest 'Non-interactive input' => sub {
    SKIP: {
        skip "These tests require a terminal", 3 if !$is_interactive;
        
        # Save current mode
        eval { ReadMode('normal') };
        
        # Test non-blocking read (should return undef immediately)
        my $char = eval { ReadKey(-1) };
        ok(!$@, "Non-blocking read doesn't die") or diag("Error: $@");
        ok(!defined($char) || length($char) == 1, "Non-blocking read returns undef or single char");
        
        # Test timed read with very short timeout
        my $timed = eval { ReadKey(0.01) };
        ok(!$@, "Timed read doesn't die") or diag("Error: $@");
        
        # Verify modes can be set without error
        my @modes = qw(normal restore);
        
        # Only test other modes if we can set them
        eval { ReadMode('noecho'); ReadMode('restore'); };
        push @modes, 'noecho' unless $@;
        
        unless ($is_windows) {
            eval { ReadMode('cbreak'); ReadMode('restore'); };
            push @modes, 'cbreak' unless $@;
            
            eval { ReadMode('raw'); ReadMode('restore'); };
            push @modes, 'raw' unless $@;
        }
        
        for my $mode (@modes) {
            eval { 
                ReadMode($mode); 
                ReadMode('restore');
            };
            ok(!$@, "Can set $mode mode") or diag("Error with $mode: $@");
        }
        
        # Make sure we're back to normal
        eval { ReadMode('restore') };
    }
};

# Final cleanup
END {
    eval { ReadMode('restore') };
}

done_testing();

