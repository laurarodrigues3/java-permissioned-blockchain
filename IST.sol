// SPDX-License-Identifier: MIT
pragma solidity ^0.8.20;

import "@openzeppelin/contracts/token/ERC20/ERC20.sol";

/**
 * @title ISTCoin
 * @dev Implementação do token nativo ERC-20 para o projeto DepChain (Fase 2)
 */
contract ISTCoin is ERC20 {
    
    /**
     * @dev Construtor do contrato autónomo.
     */
    constructor() ERC20("IST Coin", "IST") {
        // Supply de 100 milhões com 2 casas decimais.
        // Cálculo: 100.000.000 * (10 ^ 2)
        _mint(msg.sender, 100000000 * 10**2);
    }

    /**
     * @dev Requisito do guião: O token deve ter 2 casas decimais (o default do Ethereum é 18).
     */
    function decimals() public view virtual override returns (uint8) {
        return 2;
    }

    /**
     * @dev Requisito do guião: Mitigação do ataque "Approval Frontrunning".
     * Exige que a allowance seja alterada para 0 antes de ser atualizada para outro valor.
     */
    function approve(address spender, uint256 amount) public virtual override returns (bool) {
        uint256 currentAllowance = allowance(_msgSender(), spender);
        
        // Bloqueia a alteração de um valor > 0 para outro valor > 0
        require(
            currentAllowance == 0 || amount == 0,
            "ISTCoin: Para evitar frontrunning, altere a permissao para 0 primeiro"
        );
        
        return super.approve(spender, amount);
    }
}