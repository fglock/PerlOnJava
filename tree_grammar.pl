package main {
    use Data::Dumper;
    use strict;

    my $in = eval join( '', <> );
    print Dumper $in;

    my ( $rule, $result );

    $rule = TreeGrammar::AST::is_sub(
        [
            Action => sub {
                my $sub = $_[0];
                my $stmts;
                my $var;
                TreeGrammar::render(
                    [
                        Lookup => 'block',
                        [
                            Progn => [ Action => sub { $stmts = $_[0] } ],
                            [
                                Star => [
                                    Index => 0,
                                    TreeGrammar::AST::operator_eq(
                                        'infix:<=>',
                                        [
                                            Lookup => 'arguments',
                                            [
                                                And => [
                                                    Index => 0,
                                                    TreeGrammar::AST::my_var(
                                                        [
                                                            Action => sub {
                                                                $var = $_[0]->{var};
                                                              }
                                                        ]
                                                    )
                                                ],
                                                [
                                                    Index => 1,
                                                    TreeGrammar::AST::shift_arg()
                                                ],
                                                [
                                                    Action => sub {
                                                        push @{ $sub->{args} }, $var;
                                                        shift @$stmts;
                                                      }
                                                ]
                                            ]
                                        ]
                                    )
                                ]
                            ]
                        ]
                    ],
                    $sub
                );
              }
        ]
    );

    $result = TreeGrammar::scan( $rule, $in );
    print "result $result\n";
    print Dumper $in;

}

package TreeGrammar::AST {
    use strict;

    sub is_sub {
        [
            Ref => 'Perlito5::AST::Sub',
            ( @_ ? [ Progn => @_ ] : () )
        ];
    }

    sub named_sub {
        [
            Ref => 'Perlito5::AST::Sub',
            [
                And => [ Lookup => 'name', [ Not => [ Value => '' ] ] ],
                ( @_ ? [ Progn => @_ ] : () )
            ]
        ];
    }

    sub operator_eq {
        my $name = shift;
        [
            Ref => 'Perlito5::AST::Apply',
            [
                And => [ Lookup => 'code', [ Value => $name ] ],
                ( @_ ? [ Progn => @_ ] : () )
            ]
        ];
    }

    sub my_var {
        [
            Ref => 'Perlito5::AST::Decl',
            [
                And => [ Lookup => 'decl', [ Value => 'my' ] ],
                ( @_ ? [ Progn => @_ ] : () )
            ]
        ];
    }

    sub shift_arg {
        [
            Ref => 'Perlito5::AST::Apply',
            [
                And => [ Lookup => 'code', [ Value => 'shift' ] ],

                # TODO - bareword => 1, arguments => [], namespace => ''
                #     or arguments => [ @_ ]
                ( @_ ? [ Progn => @_ ] : () )
            ]
        ];
    }

}

package TreeGrammar {
    use Data::Dumper;
    use strict;

    my %dispatch;
    INIT {
        %dispatch = (
            Ref    => \&Ref,       # Ref => 'Perlito5::AST::Apply'
            Lookup => \&Lookup,    # Lookup => 'namespace'
            Index  => \&Index,     # Index  => '0'
            Value  => \&Value,     # Value => '123'
            And    => \&And,
            Or     => \&Or,
            Not    => \&Not,
            Action => \&Action,
            Progn  => \&Progn,     # same as in Lisp
            Star   => \&Star,      # same as in regex
        );
    }

    sub render {
        my ( $rule, $node ) = @_;
        return $dispatch{ $rule->[0] }->( $rule, $node );
    }

    sub scan {
        my ( $rule, $node ) = @_;
        render( $rule, $node ) if $rule;
        if ( ref($node) eq 'ARRAY' ) {
            scan( $rule, $_ ) for @$node;
        }
        elsif ( ref($node) ) {
            scan( $rule, $_ ) for values %$node;
        }
        return;
    }

    sub Action {
        my ( $rule, $node ) = @_;
        $rule->[1]->($node);
        return 1;
    }

    sub Not {
        my ( $rule, $node ) = @_;
        my $result;
        render( $rule->[1], $node ) && return;
        return 1;
    }

    sub Star {
        my ( $rule, $node ) = @_;
        my $result;
        while (1) {
            render( $rule->[1], $node ) || return;
        }
    }

    sub Progn {
        my ( $rule, $node ) = @_;
        my $result;
        for ( @$rule[ 1 .. $#$rule ] ) {
            $result = render( $_, $node );
        }
        return $result;
    }

    sub And {
        my ( $rule, $node ) = @_;
        my $result;
        for ( @$rule[ 1 .. $#$rule ] ) {
            $result = render( $_, $node ) or return;
        }
        return $result;
    }

    sub Or {
        my ( $rule, $node ) = @_;
        my $result;
        for ( @$rule[ 1 .. $#$rule ] ) {
            $result = render( $_, $node ) and return $result;
        }
        return;
    }

    sub Ref {
        my ( $rule, $node ) = @_;
        return if ref($node) ne $rule->[1];
        return 1 if !$rule->[2];
        return render( $rule->[2], $node );
    }

    sub Lookup {
        my ( $rule, $node ) = @_;
        return
             if !ref($node)
          || ref($node) eq 'ARRAY'
          || !exists( $node->{ $rule->[1] } );
        return 1 if !$rule->[2];
        return render( $rule->[2], $node->{ $rule->[1] } );
    }

    sub Index {
        my ( $rule, $node ) = @_;
        return
             if !ref($node)
          || ref($node) ne 'ARRAY'
          || !exists( $node->[ $rule->[1] ] );
        return 1 if !$rule->[2];
        return render( $rule->[2], $node->[ $rule->[1] ] );
    }

    sub Value {
        my ( $rule, $node ) = @_;
        return if ref($node) || $node ne $rule->[1];
        return 1 if !$rule->[2];
        return render( $rule->[2], $node );
    }

}
