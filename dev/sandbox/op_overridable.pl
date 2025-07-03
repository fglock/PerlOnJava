#!/usr/bin/env perl
use strict;
use warnings;
use feature 'say';

# Comprehensive list of Perl built-in operations to test
my @operations = (
    # I/O operations
    'accept', 'bind', 'binmode', 'chdir', 'chmod', 'chown', 'close', 'closedir',
    'connect', 'dbmclose', 'dbmopen', 'eof', 'fcntl', 'fileno', 'flock',
    'getc', 'getpeername', 'getsockname', 'getsockopt', 'ioctl', 'listen',
    'lstat', 'mkdir', 'open', 'opendir', 'pipe', 'print', 'printf', 'read',
    'readdir', 'readline', 'readlink', 'readpipe', 'recv', 'rename', 'rewinddir',
    'rmdir', 'say', 'seek', 'seekdir', 'select', 'send', 'setsockopt',
    'shutdown', 'socket', 'stat', 'symlink', 'sysopen', 'sysread', 'sysseek',
    'syswrite', 'tell', 'telldir', 'truncate', 'umask', 'unlink', 'utime',
    'write',
    
    # String operations
    'chomp', 'chop', 'chr', 'crypt', 'fc', 'hex', 'index', 'lc', 'lcfirst',
    'length', 'oct', 'ord', 'pack', 'quotemeta', 'reverse', 'rindex',
    'sprintf', 'substr', 'tr', 'uc', 'ucfirst', 'unpack',
    
    # Array/List operations
    'each', 'grep', 'join', 'keys', 'map', 'pop', 'push', 'reverse', 'shift',
    'sort', 'splice', 'split', 'unshift', 'values',
    
    # Hash operations
    'delete', 'exists',
    
    # Math operations
    'abs', 'atan2', 'cos', 'exp', 'int', 'log', 'rand', 'sin', 'sqrt', 'srand',
    
    # Time operations
    'gmtime', 'localtime', 'time', 'times',
    
    # Process/System operations
    'alarm', 'exec', 'exit', 'fork', 'getpgrp', 'getppid', 'getpriority',
    'kill', 'setpgrp', 'setpriority', 'sleep', 'system', 'wait', 'waitpid',
    
    # User/Group operations
    'endgrent', 'endhostent', 'endnetent', 'endprotoent', 'endpwent',
    'endservent', 'getgrent', 'getgrgid', 'getgrnam', 'gethostbyaddr',
    'gethostbyname', 'gethostent', 'getlogin', 'getnetbyaddr', 'getnetbyname',
    'getnetent', 'getprotobyname', 'getprotobynumber', 'getprotoent',
    'getpwent', 'getpwnam', 'getpwuid', 'getservbyname', 'getservbyport',
    'getservent', 'setgrent', 'sethostent', 'setnetent', 'setprotoent',
    'setpwent', 'setservent',
    
    # Scope/Package operations
    'bless', 'caller', 'die', 'dump', 'eval', 'formline', 'glob', 'import',
    'local', 'my', 'our', 'package', 'prototype', 'ref', 'require', 'reset',
    'scalar', 'state', 'tie', 'tied', 'untie', 'use', 'wantarray',
    
    # Flow control (keywords)
    'break', 'continue', 'do', 'else', 'elsif', 'for', 'foreach', 'given',
    'goto', 'if', 'last', 'next', 'redo', 'return', 'sub', 'unless', 'until',
    'when', 'while',
    
    # Special operations
    'defined', 'lock', 'pos', 'study', 'undef', 'vec',
    
    # Deprecated/Special
    'format', 'msgctl', 'msgget', 'msgrcv', 'msgsnd', 'semctl', 'semget',
    'semop', 'shmctl', 'shmget', 'shmread', 'shmwrite', 'syscall',
);

# Remove duplicates and sort
my %seen;
@operations = sort grep { !$seen{$_}++ } @operations;

print "Testing CORE::GLOBAL override support for " . scalar(@operations) . " operations...\n";
print "Perl version: $]\n";
print "Platform: $^O\n";
print "=" x 80 . "\n\n";

my %results = (
    overridable => [],
    not_overridable => [],
    keyword => [],
    error => [],
);

# Test each operation
for my $op (@operations) {
    my $test_result = test_override($op);
    push @{$results{$test_result->{category}}}, {
        name => $op,
        details => $test_result->{details}
    };
}

# Display results
print "OVERRIDABLE OPERATIONS (" . scalar(@{$results{overridable}}) . "):\n";
print "-" x 40 . "\n";
for my $op (sort { $a->{name} cmp $b->{name} } @{$results{overridable}}) {
    printf "  %-20s %s\n", $op->{name}, $op->{details} || '';
}

print "\n\nNOT OVERRIDABLE (" . scalar(@{$results{not_overridable}}) . "):\n";
print "-" x 40 . "\n";
for my $op (sort { $a->{name} cmp $b->{name} } @{$results{not_overridable}}) {
    printf "  %-20s %s\n", $op->{name}, $op->{details} || '';
}

print "\n\nKEYWORDS/COMPILE-TIME (" . scalar(@{$results{keyword}}) . "):\n";
print "-" x 40 . "\n";
for my $op (sort { $a->{name} cmp $b->{name} } @{$results{keyword}}) {
    printf "  %-20s %s\n", $op->{name}, $op->{details} || '';
}

print "\n\nERRORS (" . scalar(@{$results{error}}) . "):\n";
print "-" x 40 . "\n";
for my $op (sort { $a->{name} cmp $b->{name} } @{$results{error}}) {
    printf "  %-20s %s\n", $op->{name}, $op->{details} || '';
}

# Summary statistics
print "\n\nSUMMARY:\n";
print "=" x 40 . "\n";
printf "Total operations tested:  %d\n", scalar(@operations);
printf "Overridable:             %d (%.1f%%)\n", 
    scalar(@{$results{overridable}}),
    scalar(@{$results{overridable}}) * 100.0 / scalar(@operations);
printf "Not overridable:         %d (%.1f%%)\n",
    scalar(@{$results{not_overridable}}),
    scalar(@{$results{not_overridable}}) * 100.0 / scalar(@operations);
printf "Keywords:                %d (%.1f%%)\n",
    scalar(@{$results{keyword}}),
    scalar(@{$results{keyword}}) * 100.0 / scalar(@operations);
printf "Errors:                  %d (%.1f%%)\n",
    scalar(@{$results{error}}),
    scalar(@{$results{error}}) * 100.0 / scalar(@operations);

# Test if a specific operation can be overridden
sub test_override {
    my ($op) = @_;
    
    # Special handling for certain keywords that would cause syntax errors
    my %known_keywords = map { $_ => 1 } qw(
        if else elsif unless while until for foreach do
        my our local state package use require
        sub return break continue given when
        last next redo goto
    );
    
    if ($known_keywords{$op}) {
        return {
            category => 'keyword',
            details => 'compile-time keyword'
        };
    }
    
    # Try to override the operation
    my $override_called = 0;
    my $can_override = 0;
    my $error_msg = '';
    
    eval {
        no strict 'refs';
        no warnings;
        
        # Save original if it exists
        my $original = *{"CORE::GLOBAL::$op"}{CODE};
        
        # Try to install override
        local *{"CORE::GLOBAL::$op"} = sub {
            $override_called = 1;
            
            # Call original if possible
            if ($op eq 'time') {
                return CORE::time();
            } elsif ($op eq 'abs') {
                return CORE::abs($_[0] || 0);
            } elsif ($op eq 'length') {
                return CORE::length($_[0] || '');
            } elsif ($op eq 'sleep') {
                return 0;  # Don't actually sleep
            } elsif ($op eq 'exit') {
                return;  # Don't actually exit
            } elsif ($op eq 'die') {
                return;  # Don't actually die
            } else {
                # For others, try to call CORE version
                my $core_op = "CORE::$op";
                if (defined &$core_op) {
                    return &$core_op(@_);
                }
            }
            return;
        };
        
        # Test if override works
        if ($op eq 'time') {
            my $t = time();
            $can_override = 1 if $override_called;
        } elsif ($op eq 'abs') {
            my $v = abs(-5);
            $can_override = 1 if $override_called;
        } elsif ($op eq 'length') {
            my $l = length("test");
            $can_override = 1 if $override_called;
        } elsif ($op eq 'sleep') {
            sleep(0);
            $can_override = 1 if $override_called;
        } elsif ($op eq 'sqrt') {
            my $s = sqrt(4);
            $can_override = 1 if $override_called;
        } elsif ($op eq 'open') {
            open(my $fh, '<', '/dev/null');
            close($fh) if $fh;
            $can_override = 1 if $override_called;
        } elsif ($op eq 'close') {
            open(my $fh, '<', '/dev/null');
            close($fh);
            $can_override = 1 if $override_called;
        } elsif ($op eq 'print') {
            open(my $fh, '>', '/dev/null');
            print $fh "test";
            close($fh);
            $can_override = 1 if $override_called;
        } elsif ($op eq 'say') {
            if ($] >= 5.010) {
                open(my $fh, '>', '/dev/null');
                say $fh "test";
                close($fh);
                $can_override = 1 if $override_called;
            }
        } elsif ($op eq 'chr') {
            my $c = chr(65);
            $can_override = 1 if $override_called;
        } elsif ($op eq 'ord') {
            my $o = ord('A');
            $can_override = 1 if $override_called;
        } elsif ($op eq 'hex') {
            my $h = hex('FF');
            $can_override = 1 if $override_called;
        } elsif ($op eq 'oct') {
            my $o = oct('777');
            $can_override = 1 if $override_called;
        } elsif ($op eq 'int') {
            my $i = int(3.14);
            $can_override = 1 if $override_called;
        } elsif ($op eq 'rand') {
            my $r = rand();
            $can_override = 1 if $override_called;
        } elsif ($op eq 'stat' || $op eq 'lstat') {
            my @s = eval "$op '/dev/null'";
            $can_override = 1 if $override_called;
        } elsif ($op eq 'glob') {
            my @g = glob('/dev/null');
            $can_override = 1 if $override_called;
        } elsif ($op eq 'caller') {
            my @c = caller();
            $can_override = 1 if $override_called;
        } elsif ($op eq 'ref') {
            my $r = ref([]);
            $can_override = 1 if $override_called;
        } elsif ($op eq 'defined') {
            my $d = defined(undef);
            $can_override = 1 if $override_called;
        } elsif ($op eq 'alarm') {
            alarm(0);
            $can_override = 1 if $override_called;
        } elsif ($op eq 'chdir') {
            chdir('/');
            $can_override = 1 if $override_called;
        } elsif ($op eq 'chmod') {
            chmod(0644, '/dev/null');
            $can_override = 1 if $override_called;
        } else {
            # For operations we don't have specific tests for,
            # try a generic approach
            eval "no strict; $op();";
            $can_override = 1 if $override_called;
        }
    };
    
    if ($@) {
        $error_msg = $@;
        $error_msg =~ s/\n.*//s;  # Keep only first line
    }
    
    # Determine category
    if ($can_override) {
        return {
            category => 'overridable',
            details => ''
        };
    } elsif ($error_msg) {
        if ($error_msg =~ /syntax error|Bareword|Can't locate object method/) {
            return {
                category => 'keyword',
                details => 'syntax error'
            };
        } else {
            return {
                category => 'error',
                details => $error_msg
            };
        }
    } else {
        return {
            category => 'not_overridable',
            details => 'override not called'
        };
    }
}

