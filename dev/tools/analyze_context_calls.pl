#!/usr/bin/env perl
# Analyze acceptChild calls in emitter to extract context expectations
use strict;
use warnings;

my %patterns;
my %by_file;

while (<>) {
    # Parse: file:line:    emitterVisitor.acceptChild(node.field, RuntimeContextType.CONTEXT);
    if (/^([^:]+):(\d+):\s*.*acceptChild\(([^,]+),\s*RuntimeContextType\.(\w+)\)/) {
        my ($file, $line, $node_expr, $context) = ($1, $2, $3, $4);
        $file =~ s|.*/||;  # basename
        
        # Normalize node expression
        my $pattern = $node_expr;
        $pattern =~ s/\s+//g;
        
        push @{$patterns{$pattern}{$context}}, "$file:$line";
        $by_file{$file}++;
    }
}

print "=== Context expectations by node expression ===\n\n";
for my $pattern (sort keys %patterns) {
    my $contexts = $patterns{$pattern};
    my @ctx_list = sort keys %$contexts;
    
    if (@ctx_list == 1) {
        # Consistent context
        my $ctx = $ctx_list[0];
        my $count = scalar @{$contexts->{$ctx}};
        print "$pattern => $ctx ($count calls)\n";
    } else {
        # Multiple contexts - needs special handling
        print "*** $pattern => VARIES:\n";
        for my $ctx (@ctx_list) {
            my $locs = $contexts->{$ctx};
            print "    $ctx: " . join(", ", @$locs) . "\n";
        }
    }
}

print "\n=== Calls by file ===\n";
for my $file (sort { $by_file{$b} <=> $by_file{$a} } keys %by_file) {
    print "$file: $by_file{$file}\n";
}
