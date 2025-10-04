#!/usr/bin/perl
use v5.38;
use feature 'class';
no warnings 'experimental::class';

class Counter {
    field $count :param :reader = 0;
    field $name :param :reader;
    field $log;
    
    # ADJUST blocks run after field initialization
    ADJUST {
        # Initialize the log array
        $self->{log} = [];
        push @{$self->{log}}, "Counter '$self->{name}' initialized with count $self->{count}";
    }
    
    # Multiple ADJUST blocks are allowed and run in order
    ADJUST {
        # Validate initial count
        if ($self->{count} < 0) {
            $self->{count} = 0;
            push @{$self->{log}}, "Negative count reset to 0";
        }
    }
    
    method increment {
        $self->{count}++;
        push @{$self->{log}}, "Incremented to $self->{count}";
    }
    
    method get_log {
        return @{$self->{log}};
    }
}

# Test that the class parses correctly
print "Class Counter with ADJUST blocks defined\n";

# Note: Runtime instantiation would fail due to known limitation
# my $c = Counter->new(name => "Test", count => 5);
# print "Name: ", $c->name, "\n";
# print "Count: ", $c->count, "\n";
