#!/usr/bin/env perl
use strict;
use warnings;
use Test::More;
use File::Temp qw(tempdir);
use File::Spec;

# Create a temporary directory for testing
my $tmpdir = tempdir(CLEANUP => 1);
chdir $tmpdir or die "Cannot chdir to $tmpdir: $!";

# Create test files
my @test_files = (
    'file1.txt', 'file2.txt', 'file3.txt',
    'script1.pl', 'script2.pl', 
    'module1.pm', 'module2.pm',
    'song1.mp3', 'song2.mp3',
    'readme', 'README', 'readme.txt', 'Readme.md',
    'has space.txt', 'test e f.doc',
    '.hidden', '.hidden.txt',
    'regular'
);

foreach my $file (@test_files) {
    open my $fh, '>', $file or die "Cannot create $file: $!";
    close $fh;
}

# Create subdirectory with files
mkdir 'subdir' or die "Cannot create subdir: $!";
open my $fh, '>', 'subdir/nested.txt' or die $!;
close $fh;

subtest 'List context' => sub {
    my @txt_files = glob("*.txt");
    is(scalar(@txt_files), 5, 'Found correct number of .txt files');
    ok((grep { $_ eq 'file1.txt' } @txt_files), 'Found file1.txt');
    ok((grep { $_ eq 'has space.txt' } @txt_files), 'Found file with space');
    
    my @perl_files = glob("*.pl *.pm");
    is(scalar(@perl_files), 4, 'Found correct number of .pl and .pm files');
    
    my @mp3_files = glob("*.mp3");
    is(scalar(@mp3_files), 2, 'Found correct number of .mp3 files');
};

subtest 'Scalar context iteration' => sub {
    my $count = 0;
    my $file;
    # glob in scalar context maintains state for the SAME glob expression
    while ($file = glob("*.mp3")) {
        $count++;
        like($file, qr/\.mp3$/, 'File ends with .mp3');
    }
    is($count, 2, 'Iterated through all .mp3 files');
};

subtest 'Default to $_ when EXPR omitted' => sub {
    local $_ = "*.txt";
    my @files = glob;
    is(scalar(@files), 5, 'glob without argument uses $_');
};

subtest 'Alternate <> syntax' => sub {
    my @txt_files = <*.txt>;
    is(scalar(@txt_files), 5, 'Angle bracket syntax works');
    
    my @quoted = <"*.txt">;
    is(scalar(@quoted), 5, 'Quoted angle bracket syntax works');
};

subtest 'Hidden files behavior' => sub {
    my @all_star = glob("*");
    ok(!(grep { /^\./ } @all_star), 'glob("*") does not include hidden files');
    
    my @hidden = glob(".*");
    my $hidden_count = grep { $_ ne '.' && $_ ne '..' } @hidden;
    is($hidden_count, 2, 'glob(".*") finds hidden files');
    ok((grep { $_ eq '.hidden' } @hidden), 'Found .hidden file');
};

subtest 'Case sensitivity' => sub {
    my @readme_files = glob("readme*");
    # Standard glob is case sensitive
    is(scalar(@readme_files), 2, 'Found lowercase readme files only');
    ok((grep { $_ eq 'readme' } @readme_files), 'Found readme');
    ok((grep { $_ eq 'readme.txt' } @readme_files), 'Found readme.txt');
    ok(!(grep { $_ eq 'README' } @readme_files), 'Did not find README (case sensitive)');
};

subtest 'Whitespace in patterns' => sub {
    # Whitespace separates patterns
    my @files = glob("*.pl *.pm");
    is(scalar(@files), 4, 'Whitespace separates multiple patterns');
    
    # Files with spaces need quotes
    my @spacey1 = glob('"*e f*"');
    is(scalar(@spacey1), 1, 'Found file with space using quotes');
    is($spacey1[0], 'test e f.doc', 'Correct file with space');
    
    my @spacey2 = glob(q("*e f*"));
    is(scalar(@spacey2), 1, 'q() quoting works for files with spaces');
    
    my @spacey3 = glob('"has space.txt"');
    is(scalar(@spacey3), 1, 'Exact match with spaces works');
};

subtest 'Brace expansion' => sub {
    my @expanded = glob("{apple,tomato,cherry}={green,yellow,red}");
    is(scalar(@expanded), 9, 'Brace expansion produces 9 combinations');
    ok((grep { $_ eq 'apple=green' } @expanded), 'Found apple=green');
    ok((grep { $_ eq 'tomato=yellow' } @expanded), 'Found tomato=yellow');
    ok((grep { $_ eq 'cherry=red' } @expanded), 'Found cherry=red');
    
    # Brace expansion with existing files
    my @files = glob("{file1,file2}.txt");
    is(scalar(@files), 2, 'Brace expansion with existing files');
};

subtest 'Empty and non-matching patterns' => sub {
    my @no_match = glob("*.xyz");
    is(scalar(@no_match), 0, 'Non-matching pattern returns empty list');
    
    my @empty = glob("");
    is(scalar(@empty), 0, 'Empty pattern returns empty list');
};

subtest 'while loop implicit assignment' => sub {
    my $count = 0;
    while (glob("*.pl")) {
        $count++;
        like($_, qr/\.pl$/, '$_ contains the filename in while condition');
    }
    is($count, 2, 'while loop processed all .pl files');
};

subtest 'Absolute paths' => sub {
    my $abs_pattern = File::Spec->catfile($tmpdir, "*.txt");
    my @abs_files = glob($abs_pattern);
    is(scalar(@abs_files), 5, 'Glob with absolute path works');

    # Normalize the tmpdir path for comparison
    (my $normalized_tmpdir = $tmpdir) =~ s{//}{/}g;
    like($abs_files[0], qr/^\Q$normalized_tmpdir\E/, 'Returns absolute paths');
};

subtest 'No recursion into subdirectories' => sub {
    my @all_txt = glob("*.txt");
    ok(!(grep { /subdir/ } @all_txt), 'glob("*.txt") does not recurse into subdirectories');
    
    my @subdir_txt = glob("subdir/*.txt");
    is(scalar(@subdir_txt), 1, 'Can glob files in subdirectory explicitly');
    is($subdir_txt[0], 'subdir/nested.txt', 'Found nested file');
};

subtest 'Special characters in filenames' => sub {
    # Create files with special characters  
    my @special_files = ('test[1].txt', 'test{a}.txt', 'test*.txt');
    
    foreach my $file (@special_files) {
        open my $fh, '>', $file or die "Cannot create $file: $!";
        close $fh;
    }
    
    # Test matching files with special characters
    # Note: After creating these files, we now have 8 .txt files total
    
    # Need to escape special characters properly
    my @bracket_files = glob('test\[1\].txt');
    is(scalar(@bracket_files), 1, 'Can match literal square brackets with escaping');
    
    # Test with wildcard to match all special files
    my @test_special = glob('test[*\[\{]*.txt');
    ok(scalar(@test_special) >= 1, 'Can match files with special chars using character class');
};

subtest 'Scalar context state persistence' => sub {
    # glob maintains state PER EXPRESSION in the source code
    # Different glob calls are independent
    
    # This shows that within a single loop, state is maintained
    my @found;
    while (my $file = glob("*.pm")) {
        push @found, $file;
    }
    is(scalar(@found), 2, 'Single glob expression iterates through all files');
    
    # But separate glob calls start fresh
    my $first_call = glob("*.pm");
    my $second_call = glob("*.pm");
    is($first_call, $second_call, 'Separate glob() calls start fresh iteration');
};

subtest 'glob state per source location' => sub {
    # Each glob expression in the source maintains its own state
    
    # Create a sub that returns the next file from its own glob
    my $get_next = sub { glob("*.md") };
    
    my $file1 = $get_next->();
    my $file2 = $get_next->();
    
    # The anonymous sub contains ONE glob expression that maintains state
    is($file1, 'Readme.md', 'First call returns Readme.md');
    is($file2, undef, 'Second call returns undef (iterator exhausted)');
    
    # A new call to the same sub will restart the iteration
    my $file3 = $get_next->();
    is($file3, 'Readme.md', 'Third call restarts iteration');
    
    # But direct glob calls are independent
    my $direct1 = glob("*.md");
    my $direct2 = glob("*.md");
    is($direct1, $direct2, 'Direct glob() calls are independent and start fresh');
    
    # While loop with glob exhausts the iterator
    my $count = 0;
    $count++ while glob("*.md");
    is($count, 1, 'While loop with glob exhausts the iterator (found Readme.md)');
};

done_testing();

