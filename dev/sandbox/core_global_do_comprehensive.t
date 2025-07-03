#!/usr/bin/env perl
use strict;
use warnings;
use Test::More;
use Test::Exception;
use File::Temp qw(tempfile tempdir);
use File::Spec;
use Cwd qw(abs_path getcwd);

# Test various aspects of CORE::GLOBAL::do override

subtest 'Basic override functionality' => sub {
    plan tests => 3;

    local $@;
    my $override_called = 0;

    {
        no warnings 'redefine';
        local *CORE::GLOBAL::do = sub {
            my ($file) = @_;
            $override_called++;
            return "OVERRIDDEN: $file";
        };

        my $result = do 'test.pl';
        is($override_called, 1, "Override was called");
        is($result, "OVERRIDDEN: test.pl", "Override returned custom value");
        is($@, '', "No error set");
    }
};

subtest 'do BLOCK vs do FILE' => sub {
    plan tests => 4;

    my @calls;

    {
        no warnings 'redefine';
        local *CORE::GLOBAL::do = sub {
            my ($file) = @_;
            push @calls, { type => 'FILE', arg => $file };
            return "FILE_OVERRIDE";
        };

        # Test do BLOCK - should NOT be intercepted
        my $block_result = do {
            push @calls, { type => 'BLOCK' };
            42;
        };

        # Test do FILE - should be intercepted
        my $file_result = do 'some_file.pl';

        # Test do EXPR - should be intercepted
        my $filename = 'another_file.pl';
        my $expr_result = do $filename;

        is($block_result, 42, "do BLOCK returned correct value");
        is($file_result, "FILE_OVERRIDE", "do FILE was intercepted");
        is($expr_result, "FILE_OVERRIDE", "do EXPR was intercepted");
        is(scalar(grep { $_->{type} eq 'FILE' } @calls), 2, "Two FILE calls intercepted");
    }
};

subtest 'Error handling in override' => sub {
    plan tests => 4;

    {
        no warnings 'redefine';
        local *CORE::GLOBAL::do = sub {
            my ($file) = @_;

            if ($file =~ /forbidden/) {
                $@ = "Forbidden file access";
                return undef;
            }

            return CORE::do($file);
        };

        my $result1 = do 'forbidden.pl';
        is($result1, undef, "Forbidden file returned undef");
        like($@, qr/Forbidden file access/, "Error message set correctly");

        my $result2 = do 'allowed_but_missing.pl';
        is($result2, undef, "Missing file returned undef");
        like($@, qr/Can't locate allowed_but_missing\.pl/, "Original error preserved");
    }
};

subtest 'Real file execution with override' => sub {
    plan tests => 6;

    my $tempdir = tempdir(CLEANUP => 1);

    # Create test files
    my $file1 = File::Spec->catfile($tempdir, 'test1.pl');
    open my $fh, '>', $file1 or die $!;
    print $fh <<'PERL';
our $test_var = "loaded";
return { status => 'success', file => __FILE__ };
PERL
    close $fh;

    my $file2 = File::Spec->catfile($tempdir, 'test2.pl');
    open $fh, '>', $file2 or die $!;
    print $fh <<'PERL';
die "This file throws an error";
PERL
    close $fh;

    my %stats;

    {
        no warnings 'redefine';
        local *CORE::GLOBAL::do = sub {
            my ($file) = @_;
            $stats{calls}++;
            $stats{files}{$file}++;

            my $start = time;
            my $result = CORE::do($file);
            my $end = time;

            $stats{last_duration} = $end - $start;
            $stats{last_error} = $@ if $@;

            return $result;
        };

        # Test successful file
        my $result1 = do $file1;
        is(ref($result1), 'HASH', "File returned hash reference");
        is($result1->{status}, 'success', "File executed successfully");
        is($main::test_var, 'loaded', "File set global variable");

        # Test file with error
        my $result2 = do $file2;
        is($result2, undef, "Error file returned undef");
        like($stats{last_error}, qr/This file throws an error/, "Error was captured");

        is($stats{calls}, 2, "Override called twice");
    }
};

subtest 'Path resolution in override' => sub {
    plan tests => 5;

    my $tempdir = tempdir(CLEANUP => 1);
    my $old_cwd = getcwd();
    chdir $tempdir;

    # Create a file in current directory
    open my $fh, '>', 'local.pl' or die $!;
    print $fh '"LOCAL_FILE";';
    close $fh;

    my @resolution_log;

    {
        no warnings 'redefine';
        local *CORE::GLOBAL::do = sub {
            my ($file) = @_;

            my $resolved = $file;

            # Handle different path types
            if ($file =~ m{^/}) {
                push @resolution_log, "absolute: $file";
            } elsif ($file =~ m{^\./}) {
                push @resolution_log, "relative_explicit: $file";
            } elsif (-e $file) {
                push @resolution_log, "relative_implicit: $file";
                $resolved = "./$file";
            } else {
                push @resolution_log, "not_found: $file";
            }

            return CORE::do($resolved);
        };

        # Test different path formats
        my $r1 = do 'local.pl';              # relative implicit
        my $r2 = do './local.pl';            # relative explicit
        my $r3 = do "$tempdir/local.pl";     # absolute
        my $r4 = do 'nonexistent.pl';        # not found

        is($r1, "LOCAL_FILE", "Relative implicit path worked");
        is($r2, "LOCAL_FILE", "Relative explicit path worked");
        is($r3, "LOCAL_FILE", "Absolute path worked");
        is($r4, undef, "Nonexistent file returned undef");
        is(scalar(@resolution_log), 4, "All paths were logged");
    }

    chdir $old_cwd;
};

subtest 'Security features in override' => sub {
    plan tests => 4;

    my @security_violations;

    {
        no warnings 'redefine';
        local *CORE::GLOBAL::do = sub {
            my ($file) = @_;

            # Security checks
            if ($file =~ /\.\./) {
                push @security_violations, "path_traversal: $file";
                $@ = "Security violation: Path traversal attempt";
                return undef;
            }

            if ($file =~ /\0/) {
                push @security_violations, "null_byte: $file";
                $@ = "Security violation: Null byte in filename";
                return undef;
            }

            if (length($file) > 1000) {
                push @security_violations, "path_too_long: " . substr($file, 0, 50) . "...";
                $@ = "Security violation: Path too long";
                return undef;
            }

            return CORE::do($file);
        };

        my $r1 = do '../../../etc/passwd';
        is($r1, undef, "Path traversal blocked");

        my $r2 = do "file\0.pl";
        is($r2, undef, "Null byte blocked");

        my $r3 = do ("x" x 1001);
        is($r3, undef, "Long path blocked");

        is(scalar(@security_violations), 3, "Three security violations logged");
    }
};

done_testing();