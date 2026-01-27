use strict;
use warnings;
use Test::More;

subtest 'nested for loops with global $_' => sub {
    my @output;
    
    # Test nested for loops where both use default $_
    sub test_nested {
        my ($output_ref, @args) = @_;
        for (@args) {
            push @$output_ref, "arg: $_";
            for (split(//, $_)) {
                push @$output_ref, "  part: $_";
            }
        }
    }
    
    test_nested(\@output, "ab", "cd");
    
    is_deeply(\@output, [
        "arg: ab",
        "  part: a",
        "  part: b",
        "arg: cd",
        "  part: c",
        "  part: d"
    ], 'nested for loops preserve $_ aliasing correctly');
};

subtest 'for loop with split on global $_' => sub {
    my @output;
    
    $_ = "test";
    for (split(//, $_)) {
        push @output, $_;
    }
    
    is_deeply(\@output, ['t', 'e', 's', 't'], 
        'for loop with split($_) works correctly');
};

subtest 'statement modifier for with split' => sub {
    my @output;
    
    $_ = "xyz";
    push(@output, $_) for split(//, $_);
    
    is_deeply(\@output, ['x', 'y', 'z'], 
        'statement modifier for with split($_) works correctly');
};

subtest 'nested statement modifiers' => sub {
    my @output;
    my $text = "ab cd";
    
    # Split on space, then split each word into chars
    for my $word (split(/ /, $text)) {
        push(@output, $_) for split(//, $word);
    }
    
    is_deeply(\@output, ['a', 'b', 'c', 'd'], 
        'nested loops with split work correctly');
};

subtest 'for loop with $_ aliasing modification' => sub {
    my @arr = (1, 2, 3);
    
    # $_ should be an alias, so modifications affect original
    for (@arr) {
        $_ *= 2;
    }
    
    is_deeply(\@arr, [2, 4, 6], 
        '$_ in for loop is an alias that allows modification');
};

subtest 'nested for with modification' => sub {
    my @arr = ("ab", "cd");
    
    # Outer loop uses $_ as alias
    for (@arr) {
        my $word = $_;
        my $new = "";
        # Inner loop also uses $_
        for (split(//, $word)) {
            $new .= uc($_);
        }
        $_ = $new;
    }
    
    is_deeply(\@arr, ["AB", "CD"], 
        'nested for loops with modifications work correctly');
};

subtest 'for loop evaluates list before localizing $_' => sub {
    # This was the core bug: split($_) was seeing localized (undef) $_
    # instead of the parent scope's $_
    $_ = "abc";
    my @result;
    for (split(//, $_)) {
        push @result, $_;
    }
    
    is_deeply(\@result, ['a', 'b', 'c'],
        'for loop evaluates split($_) with parent scope $_');
};

done_testing();

