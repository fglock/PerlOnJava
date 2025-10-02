#!/usr/bin/env perl
use strict;
use warnings;
use Data::Dumper;

print "=== Pack.t Failure Analysis Tool ===\n\n";

# Run pack.t and capture output
print "Running pack.t tests...\n";
my $output = `JPERL_UNIMPLEMENTED=warn ./jperl t/op/pack.t 2>&1`;

# Parse test results
my @lines = split /\n/, $output;
my %categories;
my $total_tests = 0;
my $passing_tests = 0;
my $failing_tests = 0;

for my $line (@lines) {
    if ($line =~ /^(ok|not ok)\s+(\d+)(?:\s+-\s+(.*))?/) {
        my ($status, $test_num, $description) = ($1, $2, $3 || "");
        $total_tests++;
        
        if ($status eq 'ok') {
            $passing_tests++;
        } else {
            $failing_tests++;
            
            # Categorize the failure
            my $category = categorize_failure($description, $line);
            push @{$categories{$category}}, {
                test_num => $test_num,
                description => $description,
                line => $line
            };
        }
    }
}

# Print summary
print "\n=== Test Summary ===\n";
print "Total Tests: $total_tests\n";
print "Passing: $passing_tests (" . int($passing_tests * 100 / $total_tests) . "%)\n";
print "Failing: $failing_tests (" . int($failing_tests * 100 / $total_tests) . "%)\n";

# Print categorized failures
print "\n=== Failure Categories ===\n\n";
my @sorted_categories = sort { scalar(@{$categories{$b}}) <=> scalar(@{$categories{$a}}) } keys %categories;

for my $category (@sorted_categories) {
    my $count = scalar(@{$categories{$category}});
    my $percentage = int($count * 100 / $failing_tests);
    print "[$count tests, $percentage%] $category\n";
    
    # Show first 3 examples
    my @examples = @{$categories{$category}};
    my $show_count = $count > 3 ? 3 : $count;
    for my $i (0 .. $show_count - 1) {
        my $ex = $examples[$i];
        print "  - Test $ex->{test_num}: $ex->{description}\n";
    }
    if ($count > 3) {
        print "  ... and " . ($count - 3) . " more\n";
    }
    print "\n";
}

# Print actionable recommendations
print "\n=== Recommended Fix Priorities ===\n\n";
my $rank = 1;
for my $category (@sorted_categories) {
    my $count = scalar(@{$categories{$category}});
    next if $count < 10; # Only show categories with 10+ failures
    
    my $impact = get_impact_estimate($category, $count);
    my $complexity = get_complexity_estimate($category);
    my $priority = calculate_priority($count, $complexity);
    
    print "$rank. $category\n";
    print "   Tests affected: $count\n";
    print "   Complexity: $complexity\n";
    print "   Priority: $priority\n";
    print "   $impact\n\n";
    $rank++;
}

sub categorize_failure {
    my ($description, $line) = @_;
    
    # Pattern matching for common failure types
    return "Checksum calculations" if $description =~ /checksum|%\d+[cCuU]/i;
    return "UTF-8/Unicode handling" if $description =~ /utf-?8|unicode|\\x\{[0-9a-f]+\}/i;
    return "Endianness (big-endian)" if $description =~ /big.?endian|[>]/i;
    return "Endianness (little-endian)" if $description =~ /little.?endian|[<]/i;
    return "Native size modifiers (!)" if $description =~ /native|!/;
    return "Quad formats (q/Q)" if $description =~ /\bq\b|\bQ\b|quad|64.?bit/i;
    return "Pointer formats (p/P)" if $description =~ /\bp\b|\bP\b|pointer/i;
    return "Float/Double formats" if $description =~ /float|double|[fFdD]/;
    return "String formats (a/A/Z)" if $description =~ /\ba\b|\bA\b|\bZ\b|string|null.?pad/i;
    return "Hex string formats (h/H)" if $description =~ /\bh\b|\bH\b|hex/i;
    return "Bit string formats (b/B)" if $description =~ /\bb\b|\bB\b|bit/i;
    return "Uuencode format (u)" if $description =~ /\bu\b|uuencode/i;
    return "BER format (w)" if $description =~ /\bw\b|BER|compressed/i;
    return "Group constructs ()" if $description =~ /group|\(|\)/;
    return "Repeat counts (*)" if $description =~ /repeat|\*/;
    return "Position formats (@/.)" if $description =~ /position|@|\./;
    return "Null padding (x/X)" if $description =~ /\bx\b|\bX\b|null|pad/i;
    return "Template parsing" if $description =~ /template|format|syntax/i;
    return "Error messages" if $description =~ /error|warning|die|croak/i;
    return "Edge cases" if $description =~ /edge|boundary|overflow|underflow/i;
    return "Empty/undef handling" if $description =~ /empty|undef|null/i;
    
    # Default category
    return "Other/Uncategorized";
}

sub get_impact_estimate {
    my ($category, $count) = @_;
    
    my %impact_descriptions = (
        "Checksum calculations" => "High impact: Affects many tests, likely a core calculation bug",
        "UTF-8/Unicode handling" => "High impact: Core functionality for modern Perl",
        "Endianness (big-endian)" => "Medium impact: Cross-platform compatibility",
        "Quad formats (q/Q)" => "Medium impact: 64-bit integer support",
        "Float/Double formats" => "Medium impact: Numeric precision",
        "Pointer formats (p/P)" => "Low impact: Advanced feature, less commonly used",
        "BER format (w)" => "Low impact: Specialized format",
        "Template parsing" => "High impact: Could affect many formats",
        "Error messages" => "Low impact: Cosmetic, doesn't affect functionality",
    );
    
    return $impact_descriptions{$category} || "Impact varies depending on root cause";
}

sub get_complexity_estimate {
    my ($category) = @_;
    
    my %complexity = (
        "Checksum calculations" => "Low-Medium",
        "UTF-8/Unicode handling" => "High",
        "Endianness (big-endian)" => "Low",
        "Quad formats (q/Q)" => "Low",
        "Float/Double formats" => "Medium",
        "Pointer formats (p/P)" => "Medium",
        "BER format (w)" => "Medium",
        "Template parsing" => "Medium-High",
        "Error messages" => "Low",
        "Group constructs ()" => "Medium",
        "Position formats (@/.)" => "Low-Medium",
    );
    
    return $complexity{$category} || "Medium";
}

sub calculate_priority {
    my ($count, $complexity) = @_;
    
    # Simple priority calculation: more tests + lower complexity = higher priority
    my $complexity_score = $complexity =~ /Low/ ? 3 : $complexity =~ /Medium/ ? 2 : 1;
    my $count_score = $count > 500 ? 3 : $count > 100 ? 2 : 1;
    
    my $total = $complexity_score + $count_score;
    return $total >= 5 ? "HIGH" : $total >= 3 ? "MEDIUM" : "LOW";
}

print "\n=== Analysis Complete ===\n";
print "Review the categories above to identify high-impact fixes.\n";
print "Focus on categories with HIGH priority and many affected tests.\n";
